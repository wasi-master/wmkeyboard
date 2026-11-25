package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDownward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowUpward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Category
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiEmotions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pause
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoCamera
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Title
import com.wasimaster.wmkeyboard.core.stickers.dynamic.AnimPreset
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynFill
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynKind
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynMatch
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynMatcher
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynPackFile
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynRenderer
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynScene
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynStickerStore
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynTemplate
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynText
import com.wasimaster.wmkeyboard.core.stickers.dynamic.ElementType
import com.wasimaster.wmkeyboard.core.stickers.dynamic.LottieDoc
import com.wasimaster.wmkeyboard.core.stickers.dynamic.PackSource
import com.wasimaster.wmkeyboard.core.stickers.dynamic.PhotoFit
import com.wasimaster.wmkeyboard.core.stickers.dynamic.SceneAnimation
import com.wasimaster.wmkeyboard.core.stickers.dynamic.SceneElement
import com.wasimaster.wmkeyboard.core.stickers.dynamic.SceneFill
import com.wasimaster.wmkeyboard.core.stickers.dynamic.SceneToLottie
import com.wasimaster.wmkeyboard.core.stickers.dynamic.ShapeKind
import com.wasimaster.wmkeyboard.core.stickers.dynamic.SlotRole
import com.wasimaster.wmkeyboard.core.stickers.dynamic.TemplateMeta
import com.wasimaster.wmkeyboard.core.stickers.dynamic.TemplateProps
import com.wasimaster.wmkeyboard.core.stickers.dynamic.TextCase
import com.wasimaster.wmkeyboard.core.ui.ScalablePreview
import com.wasimaster.wmkeyboard.ime.dynstickers.DynRoutes
import com.wasimaster.wmkeyboard.ime.ui.rememberCentredRow
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** What the builder opened on, read off its route argument. */
private data class BuilderStart(
    /** The scene being edited; null for a template that has only a document (an imported Lottie file). */
    val scene: DynScene?,
    val meta: TemplateMeta,
    /** The pack a save writes into, when the template is the user's own; null saves a copy into theirs. */
    val ownPack: String?,
    /** Where its fonts and pictures come from while it is previewed. */
    val previewPack: String,
    /** The document of a template with no scene. */
    val document: JsonObject?,
)

/**
 * The dynamic sticker builder: a scene of words, shapes, art, pictures and
 * photo slots on a square canvas, each with its look and its motion, compiled
 * to a Lottie document as it changes and drawn exactly as the keyboard will
 * draw it. [arg] is `new`, `<pack>/new`, or a template's key; a template the
 * user cannot change (built-in, from a feed or the addon store) is saved as a
 * copy in their own pack.
 *
 * A template that came in as a document (no scene) opens on its rules and its
 * raw JSON instead of the canvas.
 */
@Composable
internal fun DynamicStickerBuilderScreen(arg: String, onNavigate: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { DynStickerStore.get(context) }
    val start = remember(arg) { builderStart(context, store, arg) }
    if (start == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    if (start.scene == null) {
        DocumentTemplateEditor(start, store, onBack)
    } else {
        SceneBuilder(start, store, onBack)
    }
}

private fun builderStart(context: Context, store: DynStickerStore, arg: String): BuilderStart? {
    val sample = context.getString(R.string.dynsticker_test_text_default)
    val newIn = when {
        arg == DynRoutes.NEW_TEMPLATE -> ""
        arg.endsWith("/${DynRoutes.NEW_TEMPLATE}") -> arg.substringBeforeLast('/')
        else -> null
    }
    if (newIn != null) {
        val own = newIn.takeIf { id -> store.pack(id)?.source == PackSource.USER }
        return BuilderStart(
            scene = starterScene(sample),
            meta = TemplateMeta(id = "", name = context.getString(R.string.dynsticker_builder_untitled), match = DynMatch(generic = true)),
            ownPack = own,
            previewPack = own ?: DynStickerStore.BUILTIN_ID,
            document = null,
        )
    }
    val template = store.template(arg) ?: return null
    val own = template.packId.takeIf { store.pack(it)?.source == PackSource.USER }
    return BuilderStart(
        scene = store.scene(template),
        meta = template.meta,
        ownPack = own,
        previewPack = template.packId,
        document = store.document(template),
    )
}

/** What a new template starts as: a spinning burst behind the words, which pop in and wiggle. */
private fun starterScene(sample: String) = DynScene(
    elements = listOf(
        SceneElement(
            id = "e1", type = ElementType.SHAPE, shape = ShapeKind.BURST, w = 0.95f, h = 0.95f,
            fill = 0xFFFFD60AL, fill2 = 0xFFFF9F0AL, points = 14, innerRatio = 0.78f,
            animations = listOf(SceneAnimation(AnimPreset.SPIN, duration = DynScene.DEFAULT_FRAMES, strength = 0.25f)),
        ),
        SceneElement(
            id = "e2", type = ElementType.TEXT, slot = TemplateProps.MAIN_SLOT, role = SlotRole.MAIN, text = sample,
            w = 0.82f, h = 0.46f, fill = SceneFill.WHITE, stroke = 0xFF1C1C1EL, strokeWidth = 10f,
            shadow = 0x66000000L, shadowDistance = 6f, shadowBlur = 4f,
            animations = listOf(
                SceneAnimation(AnimPreset.POP_IN, duration = 15, loop = false),
                SceneAnimation(AnimPreset.WIGGLE, delay = 15, duration = 60),
            ),
        ),
    ),
)

/** One step of the builder's history: the scene and the template's rules as they were. */
private data class BuilderSnapshot(val scene: DynScene, val meta: TemplateMeta)

/**
 * Undo and redo for the builder. A run of changes to the same thing (a slider
 * dragged, words typed, a layer dragged across the canvas) with less than
 * [MERGE_MS] between them is one step, so undo takes back the whole drag
 * rather than its last pixel.
 */
@Stable
private class BuilderHistory(first: BuilderSnapshot) {
    var current by mutableStateOf(first)
        private set
    private val undoStack = mutableStateListOf<BuilderSnapshot>()
    private val redoStack = mutableStateListOf<BuilderSnapshot>()
    private var lastKey: String? = null
    private var lastAt = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Moves to [next]; [key] names what changed, so a run of the same change is one step. */
    fun set(next: BuilderSnapshot, key: String?) {
        if (next == current) return
        val now = SystemClock.uptimeMillis()
        val merge = key != null && key == lastKey && now - lastAt < MERGE_MS
        if (!merge) {
            undoStack += current
            if (undoStack.size > HISTORY_LIMIT) undoStack.removeAt(0)
        }
        redoStack.clear()
        current = next
        lastKey = key
        lastAt = now
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack += current
        current = previous
        lastKey = null
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack += current
        current = next
        lastKey = null
    }

    private companion object {
        const val MERGE_MS = 900L
        const val HISTORY_LIMIT = 100
    }
}

private fun compileSnapshot(store: DynStickerStore, snapshot: BuilderSnapshot): SceneToLottie.Compiled? =
    runCatching {
        SceneToLottie.compile(snapshot.scene, snapshot.meta.name, snapshot.meta.match, snapshot.meta.altText, store::artFor)
    }.getOrNull()

/** The kinds of layer the Add menu offers, with what each one is for. */
private enum class LayerKind(val type: ElementType, val title: Int, val description: Int) {
    SLOT(ElementType.TEXT, R.string.dynsticker_builder_add_text, R.string.dynsticker_builder_add_text_desc),
    WORDS(ElementType.TEXT, R.string.dynsticker_builder_add_words, R.string.dynsticker_builder_add_words_desc),
    SHAPE(ElementType.SHAPE, R.string.dynsticker_builder_add_shape, R.string.dynsticker_builder_add_shape_desc),
    ART(ElementType.ART, R.string.dynsticker_builder_add_art, R.string.dynsticker_builder_add_art_desc),
    IMAGE(ElementType.IMAGE, R.string.dynsticker_builder_add_image, R.string.dynsticker_builder_add_image_desc),
    PHOTO(ElementType.PHOTO, R.string.dynsticker_builder_add_photo, R.string.dynsticker_builder_add_photo_desc),
}

private fun layerIcon(element: SceneElement): ImageVector = when (element.type) {
    ElementType.TEXT -> if (element.slot != null) Icons.Outlined.TextFields else Icons.Outlined.Title
    ElementType.SHAPE -> Icons.Outlined.Category
    ElementType.ART -> Icons.Outlined.EmojiEmotions
    ElementType.IMAGE -> Icons.Outlined.Image
    ElementType.PHOTO -> Icons.Outlined.PhotoCamera
}

private fun layerKindIcon(kind: LayerKind): ImageVector = when (kind) {
    LayerKind.SLOT -> Icons.Outlined.TextFields
    LayerKind.WORDS -> Icons.Outlined.Title
    LayerKind.SHAPE -> Icons.Outlined.Category
    LayerKind.ART -> Icons.Outlined.EmojiEmotions
    LayerKind.IMAGE -> Icons.Outlined.Image
    LayerKind.PHOTO -> Icons.Outlined.PhotoCamera
}

/** What kind of layer [element] is, in a word or two. */
@Composable
private fun layerKindLabel(element: SceneElement): String = stringResource(
    when (element.type) {
        ElementType.TEXT -> if (element.slot != null) R.string.dynsticker_builder_add_text else R.string.dynsticker_builder_add_words
        ElementType.SHAPE -> R.string.dynsticker_builder_add_shape
        ElementType.ART -> R.string.dynsticker_builder_add_art
        ElementType.IMAGE -> R.string.dynsticker_builder_add_image
        ElementType.PHOTO -> R.string.dynsticker_builder_add_photo
    },
)

/** The layer's own name: its words, its shape, its drawing or its photo's label. */
@Composable
private fun layerName(element: SceneElement): String = when (element.type) {
    ElementType.TEXT -> element.text.ifBlank { element.slot.orEmpty() }
    ElementType.SHAPE -> element.shape?.name?.lowercase()?.replace('_', ' ')?.replaceFirstChar(Char::uppercase).orEmpty()
    ElementType.ART -> element.art.orEmpty().replace('_', ' ')
    ElementType.IMAGE -> stringResource(R.string.dynsticker_builder_el_image)
    ElementType.PHOTO -> element.label.ifBlank { element.slot.orEmpty() }
}

@Composable
private fun SceneBuilder(start: BuilderStart, store: DynStickerStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember { BuilderHistory(BuilderSnapshot(checkNotNull(start.scene), start.meta)) }
    val scene = history.current.scene
    val meta = history.current.meta
    var selected by remember { mutableStateOf(scene.elements.lastOrNull { it.slot != null }?.id ?: scene.elements.lastOrNull()?.id) }
    // Empty: the preview shows the template's own words until something is typed here.
    var testText by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(true) }
    var previewPack by remember { mutableStateOf(start.previewPack) }
    var ownPack by remember { mutableStateOf(start.ownPack) }
    var picking by remember { mutableStateOf<ColorTarget?>(null) }
    var editingJson by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var nextId by remember { mutableIntStateOf((scene.elements.mapNotNull { it.id.removePrefix("e").toIntOrNull() }.maxOrNull() ?: 0) + 1) }
    val previewScale = rememberPreviewScale(PreviewScaleSlot.DYNSTICKER_BUILDER)
    val editorAnchor = remember { BringIntoViewRequester() }

    // Compiled off the main thread, the latest change only: a drag across the
    // canvas changes the scene sixty times a second, and compiling each of
    // those on the main thread is what made dragging stutter.
    val firstCompile = remember { compileSnapshot(store, history.current) }
    val compiled by produceState(firstCompile, scene, meta) {
        value = withContext(Dispatchers.Default) { compileSnapshot(store, BuilderSnapshot(scene, meta)) }
    }
    val template = remember(compiled, meta, previewPack) {
        compiled?.let {
            DynTemplate(
                previewPack,
                meta.copy(slots = it.slots, photos = it.photos),
                it.slots,
                PackSource.USER,
                meta.match.phrases.map(DynText::normalize),
            )
        }
    }

    fun setScene(next: DynScene, key: String? = null) = history.set(history.current.copy(scene = next), key)

    fun setMeta(next: TemplateMeta) = history.set(history.current.copy(meta = next), "meta")

    fun update(id: String, key: String, change: (SceneElement) -> SceneElement) {
        val now = history.current.scene
        setScene(now.copy(elements = now.elements.map { if (it.id == id) change(it) else it }), "$key:$id")
    }

    fun showEditor() {
        scope.launch {
            // After the new layer's editor is laid out.
            delay(EDITOR_SCROLL_DELAY_MS)
            editorAnchor.bringIntoView()
        }
    }

    fun add(element: SceneElement, kind: DynKind? = null) {
        val now = history.current
        history.set(
            now.copy(scene = now.scene.copy(elements = now.scene.elements + element), meta = kind?.let { now.meta.copy(kind = it) } ?: now.meta),
            null,
        )
        selected = element.id
        nextId++
        showEditor()
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                val pack = ownPack ?: myTemplatesPack(context, store) ?: return@withContext null
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                val ext = context.contentResolver.getType(uri)?.substringAfter('/')?.substringBefore('+') ?: "png"
                store.addImage(pack, bytes, ext)?.let { pack to it }
            }
            if (added == null) {
                Toast.makeText(context, R.string.dynsticker_builder_image_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // The picture lives in the user's pack now, so the preview and the save go there.
            ownPack = added.first
            previewPack = added.first
            add(SceneElement(id = "e$nextId", type = ElementType.IMAGE, image = added.second, w = 0.6f, h = 0.6f, fit = PhotoFit.CONTAIN))
        }
    }

    fun addLayer(kind: LayerKind) {
        val id = "e$nextId"
        when (kind) {
            LayerKind.SLOT -> {
                val taken = scene.elements.mapNotNull { it.slot }.toSet()
                val (slot, role) = if (TemplateProps.MAIN_SLOT !in taken) TemplateProps.MAIN_SLOT to SlotRole.MAIN else "label$nextId" to SlotRole.LABEL
                add(SceneElement(id = id, type = ElementType.TEXT, slot = slot, role = role, text = testText.ifBlank { context.getString(R.string.dynsticker_test_text_default) }, h = 0.25f, stroke = 0xFF000000L, strokeWidth = 6f))
            }
            LayerKind.WORDS -> add(
                SceneElement(id = id, type = ElementType.TEXT, text = context.getString(R.string.dynsticker_builder_words_sample), y = 0.2f, h = 0.15f, case = TextCase.KEEP),
            )
            LayerKind.SHAPE -> add(SceneElement(id = id, type = ElementType.SHAPE, shape = ShapeKind.STAR, w = 0.3f, h = 0.3f, fill = 0xFFFFD60AL, x = 0.8f, y = 0.2f))
            LayerKind.ART -> store.artIds().firstOrNull()?.let { art ->
                add(SceneElement(id = id, type = ElementType.ART, art = art, w = 0.35f, h = 0.35f, x = 0.2f, y = 0.2f))
            }
            LayerKind.IMAGE -> pickImage.launch(arrayOf("image/*"))
            LayerKind.PHOTO -> {
                val n = scene.elements.count { it.type == ElementType.PHOTO } + 1
                add(
                    SceneElement(id = id, type = ElementType.PHOTO, slot = "photo$n", w = 0.9f, h = 0.6f, label = context.getString(R.string.dynsticker_builder_photo_label)),
                    kind = DynKind.MEME,
                )
            }
        }
    }

    fun save() {
        val result = compiled ?: return
        saving = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val pack = ownPack ?: myTemplatesPack(context, store) ?: return@withContext null
                val id = if (ownPack != null && start.ownPack == ownPack && meta.id.isNotBlank()) meta.id else freshId(meta.name)
                val entry = meta.copy(id = id, file = "", scene = null, slots = result.slots, photos = result.photos)
                val sceneText = DynPackFile.json.encodeToString(DynScene.serializer(), scene)
                store.saveTemplate(pack, entry, LottieDoc.encode(result.document), sceneText)
            }
            saving = false
            if (saved == null) {
                Toast.makeText(context, R.string.dynsticker_builder_save_failed, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    context,
                    if (start.ownPack != null) R.string.dynsticker_builder_saved else R.string.dynsticker_builder_saved_copy,
                    Toast.LENGTH_SHORT,
                ).show()
                onBack()
            }
        }
    }

    val current = scene.elements.firstOrNull { it.id == selected }

    // The sticker stays in sight while the sections under it scroll, the way
    // the theme and layout editors keep their keyboard, and its corners make
    // it smaller for a phone that needs the room.
    RegisterHidingHeader()
    RegisterPinned {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val c = compiled
                val t = template
                if (c != null && t != null) {
                    ScalablePreview(
                        scale = previewScale.floatValue,
                        onScale = { previewScale.floatValue = it },
                        onScaleSettled = {
                            previewScale.floatValue = it
                            storePreviewScale(context, PreviewScaleSlot.DYNSTICKER_BUILDER, it)
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        SceneCanvas(
                            document = c.document,
                            template = t,
                            text = testText,
                            playing = playing,
                            scene = scene,
                            selected = selected,
                            onSelect = { selected = it },
                            onMove = { dx, dy ->
                                selected?.let { id ->
                                    update(id, "move") { e -> e.copy(x = (e.x + dx).coerceIn(-0.2f, 1.2f), y = (e.y + dy).coerceIn(-0.2f, 1.2f)) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(previewScale.floatValue).widthIn(max = PREVIEW_MAX_WIDTH),
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.dynsticker_builder_compile_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                BuilderToolbar(
                    canUndo = history.canUndo,
                    canRedo = history.canRedo,
                    playing = playing,
                    current = current,
                    currentPicture = { element, modifier -> LayerThumb(element, scene, previewPack, store, modifier) },
                    // One word, so the layer chip beside it keeps its name; the toast
                    // says when it went in as a copy.
                    saveLabel = stringResource(R.string.dynsticker_builder_save),
                    saving = saving || compiled == null,
                    onUndo = history::undo,
                    onRedo = history::redo,
                    onPlay = { playing = !playing },
                    onCurrent = { scope.launch { editorAnchor.bringIntoView() } },
                    onSave = ::save,
                )
            }
        }
    }

    SettingsGroup {
        item {
            FieldBlock {
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dynsticker_test_text_label)) },
                    placeholder = { Text(stringResource(R.string.dynsticker_test_text_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.dynsticker_builder_elements_title), foldKey = "dynbuilder/layers", foldOpenByDefault = true) {
        item { AddLayerButton(hasArt = remember { store.artIds().isNotEmpty() }, onAdd = ::addLayer) }
        // Topmost first, which is how a layer list reads.
        val elements = scene.elements
        for (element in elements.asReversed()) {
            item(key = element.id) {
                val at = elements.indexOfFirst { it.id == element.id }
                LayerRow(
                    element = element,
                    scene = scene,
                    previewPack = previewPack,
                    store = store,
                    selected = element.id == selected,
                    canUp = at < elements.lastIndex,
                    canDown = at > 0,
                    onSelect = {
                        selected = element.id
                        showEditor()
                    },
                    onUp = {
                        val list = history.current.scene.elements.toMutableList()
                        val i = list.indexOfFirst { it.id == element.id }
                        if (i in 0 until list.lastIndex) {
                            list.add(i + 1, list.removeAt(i))
                            setScene(history.current.scene.copy(elements = list))
                        }
                    },
                    onDown = {
                        val list = history.current.scene.elements.toMutableList()
                        val i = list.indexOfFirst { it.id == element.id }
                        if (i > 0) {
                            list.add(i - 1, list.removeAt(i))
                            setScene(history.current.scene.copy(elements = list))
                        }
                    },
                    onDuplicate = { add(element.copy(id = "e$nextId", x = (element.x + 0.05f).coerceAtMost(1f), y = (element.y + 0.05f).coerceAtMost(1f))) },
                    onDelete = {
                        setScene(history.current.scene.copy(elements = history.current.scene.elements.filter { it.id != element.id }))
                        if (selected == element.id) selected = history.current.scene.elements.lastOrNull()?.id
                    },
                )
            }
        }
    }

    Column(modifier = Modifier.bringIntoViewRequester(editorAnchor)) {
        if (current != null) {
            ElementEditor(
                element = current,
                frames = scene.frames,
                artIds = remember { store.artIds() },
                onChange = { change -> update(current.id, "edit", change) },
                onColor = { picking = it },
            )
        }
    }

    TemplateRules(
        meta = meta,
        onMeta = ::setMeta,
        document = compiled?.document,
        template = template,
        scene = scene,
        onScene = { setScene(it, "scene") },
        onColor = { picking = it },
    )

    SettingsGroup(
        stringResource(R.string.dynsticker_builder_json_title),
        info = stringResource(R.string.dynsticker_builder_json_info),
        foldKey = "dynbuilder/json",
    ) {
        item {
            val json = remember(compiled) { compiled?.let { LottieDoc.encode(it.document) }.orEmpty() }
            NavRow(
                title = R.string.dynsticker_builder_json_copy,
                subtitle = android.text.format.Formatter.formatShortFileSize(context, json.length.toLong()),
            ) { copyText(context, json) }
        }
        item {
            NavRow(title = R.string.dynsticker_builder_json_edit, subtitle = stringResource(R.string.dynsticker_builder_json_edit_subtitle)) {
                editingJson = true
            }
        }
    }

    picking?.let { target ->
        val element = scene.elements.firstOrNull { it.id == target.elementId }
        val initial = when (target.field) {
            ColorField.FILL -> element?.fill
            ColorField.FILL2 -> element?.fill2
            ColorField.STROKE -> element?.stroke
            ColorField.SHADOW -> element?.shadow
            ColorField.BACKGROUND -> scene.background?.color
            ColorField.BACKGROUND2 -> scene.background?.color2
        } ?: SceneFill.WHITE
        ColorPickerDialog(
            title = stringResource(target.field.label),
            initial = initial,
            supportsAlpha = true,
            showReset = target.field.clearable,
            onPick = { argb ->
                picking = null
                applyColor(target, argb, history.current.scene)?.let { setScene(it) }
            },
            onReset = {
                picking = null
                applyColor(target, null, history.current.scene)?.let { setScene(it) }
            },
            onDismiss = { picking = null },
        )
    }
    if (editingJson) {
        JsonEditorDialog(
            initial = compiled?.let { LottieDoc.encode(it.document) }.orEmpty(),
            detaches = true,
            onDismiss = { editingJson = false },
            onUse = { text ->
                val doc = LottieDoc.parse(text)
                if (doc == null) {
                    Toast.makeText(context, R.string.dynsticker_builder_json_invalid, Toast.LENGTH_LONG).show()
                } else {
                    editingJson = false
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            val pack = ownPack ?: myTemplatesPack(context, store) ?: return@withContext null
                            val id = if (ownPack != null && start.ownPack == ownPack && meta.id.isNotBlank()) meta.id else freshId(meta.name)
                            // A document of the user's own words now: its slots are read off it again.
                            val entry = meta.copy(id = id, file = "", scene = null, slots = emptyList(), photos = emptyList())
                            store.saveTemplate(pack, entry, LottieDoc.encode(doc), null)
                        }
                        Toast.makeText(
                            context,
                            if (saved != null) R.string.dynsticker_builder_saved else R.string.dynsticker_builder_save_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (saved != null) onBack()
                    }
                }
            },
        )
    }
}

/** Under the pinned sticker: undo, redo, play, the layer being edited, and save. */
@Composable
@Suppress("LongParameterList")
private fun BuilderToolbar(
    canUndo: Boolean,
    canRedo: Boolean,
    playing: Boolean,
    current: SceneElement?,
    currentPicture: @Composable (SceneElement, Modifier) -> Unit,
    saveLabel: String,
    saving: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPlay: () -> Unit,
    onCurrent: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(CommonR.string.common_undo))
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = stringResource(R.string.code_redo_desc))
        }
        IconButton(onClick = onPlay) {
            Icon(
                if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.dynsticker_builder_pause else R.string.dynsticker_builder_play),
            )
        }
        // The layer being edited, always in sight: a tap goes to its settings.
        Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            if (current != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onCurrent),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // The layer itself, drawn alone, the way its row in the list shows it.
                        currentPicture(current, Modifier.size(TOOLBAR_THUMB_SIZE))
                        Text(
                            layerName(current).ifBlank { layerKindLabel(current) },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        FilledTonalButton(onClick = onSave, enabled = !saving) { Text(saveLabel, maxLines = 1) }
    }
}

/** Add a layer: one button, and a menu that says what each kind of layer is. */
@Composable
private fun AddLayerButton(hasArt: Boolean, onAdd: (LayerKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.dynsticker_builder_add_layer), modifier = Modifier.padding(start = 8.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (kind in LayerKind.entries) {
                if (kind == LayerKind.ART && !hasArt) continue
                DropdownMenuItem(
                    leadingIcon = { Icon(layerKindIcon(kind), contentDescription = null) },
                    text = {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(stringResource(kind.title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(kind.description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onAdd(kind)
                    },
                )
            }
        }
    }
}

/** One layer in the list: a picture of it alone, what it is, and its order and menu. */
@Composable
@Suppress("LongParameterList")
private fun LayerRow(
    element: SceneElement,
    scene: DynScene,
    previewPack: String,
    store: DynStickerStore,
    selected: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onSelect: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LayerThumb(element, scene, previewPack, store, Modifier.size(LAYER_THUMB_SIZE))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(layerIcon(element), contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(layerKindLabel(element), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val name = layerName(element)
            if (name.isNotBlank()) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(LAYER_BUTTON_SIZE)) {
            Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.dynsticker_builder_layer_up), modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(LAYER_BUTTON_SIZE)) {
            Icon(Icons.Outlined.ArrowDownward, contentDescription = stringResource(R.string.dynsticker_builder_layer_down), modifier = Modifier.size(20.dp))
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(LAYER_BUTTON_SIZE)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.dynsticker_builder_layer_more), modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    text = { Text(stringResource(R.string.dynsticker_builder_layer_duplicate)) },
                    onClick = {
                        menu = false
                        onDuplicate()
                    },
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    text = { Text(stringResource(CommonR.string.common_delete)) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** [element] drawn on its own, still and whole (no motion), as the keyboard would draw it. */
@Composable
private fun LayerThumb(element: SceneElement, scene: DynScene, previewPack: String, store: DynStickerStore, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, element, scene.width, scene.height, previewPack) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val alone = scene.copy(elements = listOf(element.copy(animations = emptyList())), background = null, frames = 1)
                val compiled = SceneToLottie.compile(alone, "", DynMatch(), "", store::artFor) ?: return@runCatching null
                val template = DynTemplate(
                    previewPack,
                    TemplateMeta(id = "layer", name = "", slots = compiled.slots, photos = compiled.photos),
                    compiled.slots,
                    PackSource.USER,
                    emptyList(),
                )
                DynAppRender.renderer(context).prepareDocument(compiled.document, template, DynFill(emptyMap()))?.use { prepared ->
                    prepared.render(prepared.posterProgress, LAYER_THUMB_PX).asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
}

private val LAYER_THUMB_SIZE = 44.dp
private val TOOLBAR_THUMB_SIZE = 28.dp
private val LAYER_BUTTON_SIZE = 36.dp
private val PREVIEW_MAX_WIDTH = 420.dp
private const val LAYER_THUMB_PX = 128
private const val EDITOR_SCROLL_DELAY_MS = 120L

private fun freshId(name: String): String {
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifBlank { "t" }
    return slug + "_" + UUID.randomUUID().toString().take(4)
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Lottie", text))
    Toast.makeText(context, R.string.dynsticker_builder_json_copied, Toast.LENGTH_SHORT).show()
}

/**
 * The preview: drawn by the keyboard's own renderer, with the selected
 * element framed and draggable.
 *
 * Playback runs on a clock of its own, so a change to the scene (a new
 * document) carries on from the same moment instead of starting over on the
 * empty first frame. While a layer is dragged it shows the still, where
 * everything is in place. Frames are drawn off the main thread, the latest
 * one only, so a slow frame never queues the ones behind it.
 */
@Composable
@Suppress("LongParameterList")
private fun SceneCanvas(
    document: JsonObject,
    template: DynTemplate,
    text: String,
    playing: Boolean,
    scene: DynScene,
    selected: String?,
    onSelect: (String?) -> Unit,
    onMove: (Float, Float) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().widthIn(max = PREVIEW_MAX_WIDTH),
) {
    val context = LocalContext.current
    val lock = remember { Mutex() }
    val prepared by produceState<DynRenderer.Prepared?>(null, document, template, text) {
        val fill = if (text.isBlank()) DynFill(emptyMap()) else DynMatcher.fillFor(template, text) ?: DynFill(emptyMap())
        val made = withContext(Dispatchers.Default) {
            runCatching { DynAppRender.renderer(context).prepareDocument(document, template, fill) }.getOrNull()
        }
        value = made
        // Closed once no frame is being drawn from it, off the main thread.
        awaitDispose { made?.let { p -> kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { lock.withLock { p.close() } } } }
    }
    val buffers = remember { arrayOfNulls<Bitmap>(2) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    var frameNo by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    // How far the finger has moved the layer since the scene was last told.
    var pending by remember { mutableStateOf(Offset.Zero) }
    val clockStart = remember { System.nanoTime() }
    val durationMs = prepared?.info?.durationMs
    val moving = prepared?.animated == true
    val poster = prepared?.posterProgress ?: 1f
    LaunchedEffect(playing, dragging, durationMs, moving, poster) {
        if (!playing || dragging || !moving || durationMs == null) {
            progress = poster
            return@LaunchedEffect
        }
        val length = durationMs.coerceAtLeast(1).toLong()
        while (true) {
            val nowMs = withFrameNanos { it } / NANOS_PER_MS
            progress = ((nowMs - clockStart / NANOS_PER_MS).mod(length)) / length.toFloat()
            delay(FRAME_MS)
        }
    }
    LaunchedEffect(prepared) {
        val p = prepared ?: return@LaunchedEffect
        snapshotFlow { progress }.conflate().collect { at ->
            val index = frameNo % 2
            val target = buffers[index] ?: Bitmap.createBitmap(PREVIEW_PX, PREVIEW_PX, Bitmap.Config.ARGB_8888).also { buffers[index] = it }
            val drawn = withContext(Dispatchers.Default) { lock.withLock { runCatching { p.draw(at, target) }.isSuccess } }
            if (drawn) {
                shown = target
                frameNo++
            }
        }
    }
    val elements by rememberUpdatedState(scene.elements)
    val currentSelected by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val move by rememberUpdatedState(onMove)
    val outline = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .aspectRatio(scene.width.toFloat() / scene.height.coerceAtLeast(1))
            .clip(RoundedCornerShape(12.dp))
            .background(Checker)
            .pointerInput(Unit) {
                fun hit(offset: Offset): SceneElement? {
                    val fx = offset.x / size.width
                    val fy = offset.y / size.height
                    return elements.lastOrNull { e -> fx in (e.x - e.w / 2)..(e.x + e.w / 2) && fy in (e.y - e.h / 2)..(e.y + e.h / 2) }
                }
                detectTapGestures { offset -> select(hit(offset)?.id) }
            }
            .pointerInput(Unit) {
                var lastFlush = 0L
                fun flush() {
                    val delta = pending
                    if (delta != Offset.Zero) {
                        pending = Offset.Zero
                        move(delta.x, delta.y)
                    }
                    lastFlush = SystemClock.uptimeMillis()
                }
                detectDragGestures(
                    onDragStart = { offset ->
                        // Dragging a layer that isn't selected picks it up.
                        val fx = offset.x / size.width
                        val fy = offset.y / size.height
                        val hit = elements.lastOrNull { e -> fx in (e.x - e.w / 2)..(e.x + e.w / 2) && fy in (e.y - e.h / 2)..(e.y + e.h / 2) }
                        if (hit != null && hit.id != currentSelected) select(hit.id)
                        dragging = true
                    },
                    onDragEnd = {
                        flush()
                        dragging = false
                    },
                    onDragCancel = {
                        flush()
                        dragging = false
                    },
                ) { change, amount ->
                    change.consume()
                    pending += Offset(amount.x / size.width, amount.y / size.height)
                    // The frame follows the finger at once; the scene, which
                    // recompiles the whole sticker, hears of it a few times a second.
                    if (SystemClock.uptimeMillis() - lastFlush >= MOVE_FLUSH_MS) flush()
                }
            },
    ) {
        // Recomposed by frameNo, which moves with every drawn frame.
        frameNo.let { _ ->
            shown?.let { Image(it.asImageBitmap(), contentDescription = template.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        scene.elements.firstOrNull { it.id == selected }?.let { e ->
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val x = e.x + pending.x
                val y = e.y + pending.y
                Box(
                    modifier = Modifier
                        .padding(start = maxWidth * (x - e.w / 2).coerceIn(0f, 1f), top = maxHeight * (y - e.h / 2).coerceIn(0f, 1f))
                        .size(maxWidth * e.w.coerceIn(0.02f, 1f), maxHeight * e.h.coerceIn(0.02f, 1f))
                        .border(1.5.dp, outline, RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

private val Checker = Color(0x14808080)
private const val PREVIEW_PX = 512
private const val FRAME_MS = 33L
private const val MOVE_FLUSH_MS = 90L
private const val NANOS_PER_MS = 1_000_000L

/** Which colour of what the picker is choosing. */
private data class ColorTarget(val elementId: String?, val field: ColorField)

private enum class ColorField(val label: Int, val clearable: Boolean) {
    FILL(R.string.dynsticker_builder_fill, false),
    FILL2(R.string.dynsticker_builder_fill2, true),
    STROKE(R.string.dynsticker_builder_stroke, true),
    SHADOW(R.string.dynsticker_builder_shadow, true),
    BACKGROUND(R.string.dynsticker_builder_background, true),
    BACKGROUND2(R.string.dynsticker_builder_background2, true),
}

private fun applyColor(target: ColorTarget, argb: Long?, scene: DynScene): DynScene? {
    if (target.field == ColorField.BACKGROUND) {
        return scene.copy(background = argb?.let { (scene.background ?: SceneFill()).copy(color = it) })
    }
    if (target.field == ColorField.BACKGROUND2) {
        val background = scene.background ?: return null
        return scene.copy(background = background.copy(color2 = argb))
    }
    val id = target.elementId ?: return null
    return scene.copy(
        elements = scene.elements.map { e ->
            if (e.id != id) {
                e
            } else {
                when (target.field) {
                    ColorField.FILL -> e.copy(fill = argb ?: e.fill)
                    ColorField.FILL2 -> e.copy(fill2 = argb)
                    ColorField.STROKE -> e.copy(stroke = argb, strokeWidth = if (argb != null && e.strokeWidth == 0f) DEFAULT_STROKE else e.strokeWidth)
                    ColorField.SHADOW -> e.copy(shadow = argb, shadowDistance = if (argb != null && e.shadowDistance == 0f) DEFAULT_SHADOW else e.shadowDistance)
                    else -> e
                }
            }
        },
    )
}

private const val DEFAULT_STROKE = 6f
private const val DEFAULT_SHADOW = 5f

/** The fonts the builder offers by name; any installed font can be typed in too. */
private val BuilderFonts = listOf("DynaPuff", "Anton", "sans-serif-black", "sans-serif", "serif", "monospace", "cursive")

@Composable
private fun ElementEditor(
    element: SceneElement,
    frames: Int,
    artIds: List<String>,
    onChange: ((SceneElement) -> SceneElement) -> Unit,
    onColor: (ColorTarget) -> Unit,
) {
    val isText = element.type == ElementType.TEXT
    SettingsGroup(stringResource(R.string.dynsticker_builder_selected_title), foldKey = "dynbuilder/selected", foldOpenByDefault = true) {
        if (isText) {
            item {
                FieldBlock {
                    OutlinedTextField(
                        value = element.text,
                        onValueChange = { v -> onChange { it.copy(text = v) } },
                        label = {
                            Text(stringResource(if (element.slot != null) R.string.dynsticker_builder_sample_label else R.string.dynsticker_builder_words_label))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchLine(stringResource(R.string.dynsticker_builder_typed_here), element.slot != null) { on ->
                        onChange { it.copy(slot = if (on) TemplateProps.MAIN_SLOT else null, role = SlotRole.MAIN) }
                    }
                    if (element.slot != null) {
                        ChipLine(
                            stringResource(R.string.dynsticker_builder_role),
                            SlotRole.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
                            element.role,
                        ) { role ->
                            onChange {
                                it.copy(role = role, slot = if (role == SlotRole.MAIN) TemplateProps.MAIN_SLOT else role.name.lowercase())
                            }
                        }
                        OutlinedTextField(
                            value = element.slot.orEmpty(),
                            onValueChange = { v -> onChange { it.copy(slot = v.filter { c -> c.isLetterOrDigit() || c == '_' }.ifBlank { TemplateProps.MAIN_SLOT }) } },
                            singleLine = true,
                            label = { Text(stringResource(R.string.dynsticker_builder_slot_id)) },
                            supportingText = { Text(stringResource(R.string.dynsticker_builder_slot_id_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                FieldBlock {
                    ChipLine(stringResource(R.string.dynsticker_builder_font), BuilderFonts.map { it to it }, element.font) { f -> onChange { it.copy(font = f) } }
                    OutlinedTextField(
                        value = element.font,
                        onValueChange = { v -> onChange { it.copy(font = v) } },
                        singleLine = true,
                        label = { Text(stringResource(R.string.dynsticker_builder_font_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ChipLine(
                        stringResource(R.string.dynsticker_builder_case),
                        listOf(
                            TextCase.KEEP to stringResource(R.string.dynsticker_builder_case_keep),
                            TextCase.UPPER to "ABC",
                            TextCase.LOWER to "abc",
                            TextCase.TITLE to "Abc",
                        ),
                        element.case,
                    ) { c -> onChange { it.copy(case = c) } }
                    ChipLine(
                        stringResource(R.string.dynsticker_builder_align),
                        listOf(
                            0 to stringResource(R.string.dynsticker_builder_align_left),
                            SceneElement.ALIGN_CENTER to stringResource(R.string.dynsticker_builder_align_centre),
                            1 to stringResource(R.string.dynsticker_builder_align_right),
                        ),
                        element.align,
                    ) { a -> onChange { it.copy(align = a) } }
                    ChipLine(
                        stringResource(R.string.dynsticker_builder_valign),
                        listOf(
                            0 to stringResource(R.string.dynsticker_builder_valign_top),
                            1 to stringResource(R.string.dynsticker_builder_valign_middle),
                            2 to stringResource(R.string.dynsticker_builder_valign_bottom),
                        ),
                        element.vAlign,
                    ) { a -> onChange { it.copy(vAlign = a) } }
                    SliderLine(stringResource(R.string.dynsticker_builder_max_lines), element.maxLines.toFloat(), 1f..8f, steps = 6, format = { it.roundToInt().toString() }) { v ->
                        onChange { it.copy(maxLines = v.roundToInt()) }
                    }
                    SliderLine(stringResource(R.string.dynsticker_builder_max_chars), element.maxChars.toFloat(), 1f..200f, format = { it.roundToInt().toString() }) { v ->
                        onChange { it.copy(maxChars = v.roundToInt()) }
                    }
                    SliderLine(stringResource(R.string.dynsticker_builder_min_size), element.minSize, 0f..240f, format = ::sizeLabel) { v -> onChange { it.copy(minSize = v) } }
                    SliderLine(stringResource(R.string.dynsticker_builder_max_size), element.maxSize, 0f..320f, format = ::sizeLabel) { v -> onChange { it.copy(maxSize = v) } }
                    SliderLine(stringResource(R.string.dynsticker_builder_line_height), element.lineHeight, 0.6f..2f, format = { "%.2f×".format(it) }) { v ->
                        onChange { it.copy(lineHeight = v) }
                    }
                    SliderLine(stringResource(R.string.dynsticker_builder_tracking), element.tracking, -100f..400f, format = { it.roundToInt().toString() }) { v ->
                        onChange { it.copy(tracking = v) }
                    }
                }
            }
        }
        if (element.type == ElementType.SHAPE) {
            item {
                FieldBlock {
                    ChipLine(
                        stringResource(R.string.dynsticker_builder_shape),
                        ShapeKind.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
                        element.shape ?: ShapeKind.RECT,
                    ) { s -> onChange { it.copy(shape = s) } }
                    SliderLine(stringResource(R.string.dynsticker_builder_points), element.points.toFloat(), 3f..24f, steps = 20, format = { it.roundToInt().toString() }) { v ->
                        onChange { it.copy(points = v.roundToInt()) }
                    }
                    SliderLine(stringResource(R.string.dynsticker_builder_inner), element.innerRatio, 0.1f..0.95f, format = { "%.0f%%".format(it * 100) }) { v ->
                        onChange { it.copy(innerRatio = v) }
                    }
                    SliderLine(stringResource(R.string.dynsticker_builder_corner), element.corner, 0f..200f, format = { it.roundToInt().toString() }) { v ->
                        onChange { it.copy(corner = v) }
                    }
                }
            }
        }
        if (element.type == ElementType.ART && artIds.isNotEmpty()) {
            item {
                FieldBlock {
                    ChipLine(stringResource(R.string.dynsticker_builder_art), artIds.map { it to it }, element.art ?: artIds.first()) { a -> onChange { it.copy(art = a) } }
                }
            }
        }
        if (element.type == ElementType.PHOTO || element.type == ElementType.IMAGE) {
            item {
                FieldBlock {
                    if (element.type == ElementType.PHOTO) {
                        OutlinedTextField(
                            value = element.label,
                            onValueChange = { v -> onChange { it.copy(label = v) } },
                            singleLine = true,
                            label = { Text(stringResource(R.string.dynsticker_builder_photo_name)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ChipLine(
                        stringResource(R.string.dynsticker_builder_fit),
                        listOf(PhotoFit.COVER to stringResource(R.string.dynsticker_builder_fit_cover), PhotoFit.CONTAIN to stringResource(R.string.dynsticker_builder_fit_contain)),
                        element.fit,
                    ) { f -> onChange { it.copy(fit = f) } }
                }
            }
        }
        if (element.type != ElementType.PHOTO && element.type != ElementType.IMAGE) {
            item {
                FieldBlock {
                    ColorLine(stringResource(R.string.dynsticker_builder_fill), element.fill) { onColor(ColorTarget(element.id, ColorField.FILL)) }
                    if (!isText) ColorLine(stringResource(R.string.dynsticker_builder_fill2), element.fill2) { onColor(ColorTarget(element.id, ColorField.FILL2)) }
                    ColorLine(stringResource(R.string.dynsticker_builder_stroke), element.stroke) { onColor(ColorTarget(element.id, ColorField.STROKE)) }
                    if (element.stroke != null) {
                        SliderLine(stringResource(R.string.dynsticker_builder_stroke_width), element.strokeWidth, 0f..40f, format = { it.roundToInt().toString() }) { v ->
                            onChange { it.copy(strokeWidth = v) }
                        }
                    }
                    ColorLine(stringResource(R.string.dynsticker_builder_shadow), element.shadow) { onColor(ColorTarget(element.id, ColorField.SHADOW)) }
                    if (element.shadow != null) {
                        SliderLine(stringResource(R.string.dynsticker_builder_shadow_distance), element.shadowDistance, 0f..40f, format = { it.roundToInt().toString() }) { v ->
                            onChange { it.copy(shadowDistance = v) }
                        }
                        SliderLine(stringResource(R.string.dynsticker_builder_shadow_blur), element.shadowBlur, 0f..40f, format = { it.roundToInt().toString() }) { v ->
                            onChange { it.copy(shadowBlur = v) }
                        }
                    }
                }
            }
        }
        item {
            FieldBlock {
                SliderLine(stringResource(R.string.dynsticker_builder_x), element.x, -0.2f..1.2f, format = ::percent) { v -> onChange { it.copy(x = v) } }
                SliderLine(stringResource(R.string.dynsticker_builder_y), element.y, -0.2f..1.2f, format = ::percent) { v -> onChange { it.copy(y = v) } }
                SliderLine(stringResource(R.string.dynsticker_builder_w), element.w, 0.02f..1.5f, format = ::percent) { v -> onChange { it.copy(w = v) } }
                SliderLine(stringResource(R.string.dynsticker_builder_h), element.h, 0.02f..1.5f, format = ::percent) { v -> onChange { it.copy(h = v) } }
                SliderLine(stringResource(R.string.dynsticker_builder_rotation), element.rotation, -180f..180f, format = { "${it.roundToInt()}°" }) { v ->
                    onChange { it.copy(rotation = v) }
                }
                SliderLine(stringResource(R.string.dynsticker_builder_opacity), element.opacity, 0f..1f, format = ::percent) { v -> onChange { it.copy(opacity = v) } }
                SwitchLine(stringResource(R.string.dynsticker_builder_flip), element.flipX) { on -> onChange { it.copy(flipX = on) } }
            }
        }
    }
    SettingsGroup(stringResource(R.string.dynsticker_builder_motion_title), foldKey = "dynbuilder/motion", foldOpenByDefault = true) {
        element.animations.forEachIndexed { index, animation ->
            item(key = "${element.id}_$index") {
                FieldBlock {
                    ChipLine(
                        stringResource(R.string.dynsticker_builder_animation, index + 1),
                        AnimPreset.entries.map { it to presetLabel(it) },
                        animation.preset,
                    ) { p -> onChange { e -> e.copy(animations = e.animations.mapIndexed { i, a -> if (i == index) a.copy(preset = p) else a }) } }
                    SliderLine(stringResource(R.string.dynsticker_builder_delay), animation.delay.toFloat(), 0f..frames.coerceAtLeast(2).toFloat(), format = { frameLabel(it) }) { v ->
                        onChange { e -> e.copy(animations = e.animations.mapIndexed { i, a -> if (i == index) a.copy(delay = v.roundToInt()) else a }) }
                    }
                    SliderLine(stringResource(R.string.dynsticker_builder_duration), animation.duration.toFloat(), 1f..frames.coerceAtLeast(2).toFloat(), format = { frameLabel(it) }) { v ->
                        onChange { e -> e.copy(animations = e.animations.mapIndexed { i, a -> if (i == index) a.copy(duration = v.roundToInt().coerceAtLeast(1)) else a }) }
                    }
                    SliderLine(stringResource(R.string.dynsticker_builder_strength), animation.strength, 0.1f..3f, format = { "%.1f×".format(it) }) { v ->
                        onChange { e -> e.copy(animations = e.animations.mapIndexed { i, a -> if (i == index) a.copy(strength = v) else a }) }
                    }
                    if (animation.preset == AnimPreset.SLIDE_IN) {
                        ChipLine(
                            stringResource(R.string.dynsticker_builder_direction),
                            listOf(
                                0 to stringResource(R.string.dynsticker_builder_align_left),
                                1 to stringResource(R.string.dynsticker_builder_align_right),
                                2 to stringResource(R.string.dynsticker_builder_valign_top),
                                3 to stringResource(R.string.dynsticker_builder_valign_bottom),
                            ),
                            animation.direction,
                        ) { d -> onChange { e -> e.copy(animations = e.animations.mapIndexed { i, a -> if (i == index) a.copy(direction = d) else a }) } }
                    }
                    SwitchLine(stringResource(R.string.dynsticker_builder_loop), animation.loop) { on ->
                        onChange { e -> e.copy(animations = e.animations.mapIndexed { i, a -> if (i == index) a.copy(loop = on) else a }) }
                    }
                    TextButton(onClick = { onChange { e -> e.copy(animations = e.animations.filterIndexed { i, _ -> i != index }) } }) {
                        Text(stringResource(R.string.dynsticker_builder_remove_animation))
                    }
                }
            }
        }
        item {
            TextButton(
                onClick = { onChange { e -> e.copy(animations = e.animations + SceneAnimation(AnimPreset.BOUNCE)) } },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.dynsticker_builder_add_animation)) }
        }
    }
}

private fun sizeLabel(value: Float): String = if (value <= 0.5f) "auto" else value.roundToInt().toString()

private fun percent(value: Float): String = "${(value * 100).roundToInt()}%"

/**
 * [scene] at [fps] frames a second, running at the same speed: its length
 * and every motion's start and length are counted in frames, so each is
 * scaled with the rate and stays the same number of seconds.
 */
private fun atFrameRate(scene: DynScene, fps: Int): DynScene {
    val ratio = fps / scene.fps.coerceAtLeast(1).toFloat()
    fun frames(count: Int) = (count * ratio).roundToInt()
    return scene.copy(
        fps = fps,
        frames = frames(scene.frames).coerceAtLeast(2),
        elements = scene.elements.map { element ->
            if (element.animations.isEmpty()) {
                element
            } else {
                element.copy(animations = element.animations.map { it.copy(delay = frames(it.delay), duration = frames(it.duration).coerceAtLeast(1)) })
            }
        },
    )
}

private fun frameLabel(frames: Float): String = "${frames.roundToInt()}f"

@Composable
private fun presetLabel(preset: AnimPreset): String = preset.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

/** What a template is offered for, how long it runs, and what it is drawn on. */
@Composable
private fun TemplateRules(
    meta: TemplateMeta,
    onMeta: (TemplateMeta) -> Unit,
    document: JsonObject? = null,
    template: DynTemplate? = null,
    scene: DynScene?,
    onScene: (DynScene) -> Unit,
    onColor: (ColorTarget) -> Unit,
) {
    SettingsGroup(stringResource(R.string.dynsticker_builder_rules_title), foldKey = "dynbuilder/rules") {
        item {
            FieldBlock {
                OutlinedTextField(
                    value = meta.name,
                    onValueChange = { onMeta(meta.copy(name = it)) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dynsticker_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ChipLine(
                    stringResource(R.string.dynsticker_builder_kind),
                    listOf(DynKind.STICKER to stringResource(R.string.dynsticker_kind_sticker), DynKind.MEME to stringResource(R.string.dynsticker_kind_meme)),
                    meta.kind,
                ) { onMeta(meta.copy(kind = it)) }
                ListField(stringResource(R.string.dynsticker_builder_phrases), stringResource(R.string.dynsticker_builder_phrases_hint), meta.match.phrases) {
                    onMeta(meta.copy(match = meta.match.copy(phrases = it)))
                }
                ListField(stringResource(R.string.dynsticker_builder_concepts), stringResource(R.string.dynsticker_builder_concepts_hint), meta.match.concepts) {
                    onMeta(meta.copy(match = meta.match.copy(concepts = it)))
                }
                SwitchLine(stringResource(R.string.dynsticker_builder_generic), meta.match.generic) { onMeta(meta.copy(match = meta.match.copy(generic = it))) }
                SliderLine(
                    stringResource(R.string.dynsticker_builder_max_words),
                    meta.match.maxWords.toFloat(), 0f..20f, steps = 19,
                    format = { if (it < 0.5f) "—" else it.roundToInt().toString() },
                ) { onMeta(meta.copy(match = meta.match.copy(maxWords = it.roundToInt()))) }
                OutlinedTextField(
                    value = meta.altText,
                    onValueChange = { onMeta(meta.copy(altText = it)) },
                    label = { Text(stringResource(R.string.dynsticker_builder_alt)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (scene != null) {
            item {
                FieldBlock {
                    SwitchLine(stringResource(R.string.dynsticker_builder_moving), scene.frames > 1) { on ->
                        onScene(scene.copy(frames = if (on) DynScene.DEFAULT_FRAMES else 1))
                    }
                    if (scene.frames > 1) {
                        SliderLine(
                            stringResource(R.string.dynsticker_builder_length),
                            scene.frames / scene.fps.coerceAtLeast(1).toFloat(), 0.5f..10f,
                            format = { "%.1f s".format(it) },
                        ) { seconds -> onScene(scene.copy(frames = (seconds * scene.fps).roundToInt().coerceAtLeast(2))) }
                        ChipLine(stringResource(R.string.dynsticker_builder_fps), listOf(24, 25, 30, 60).map { it to "$it" }, scene.fps) { fps ->
                            onScene(atFrameRate(scene, fps))
                        }
                        SwitchLine(stringResource(R.string.dynsticker_builder_still_only), meta.stillOnly) { onMeta(meta.copy(stillOnly = it)) }
                        if (document != null && template != null) {
                            PosterPicker(document, template, meta.poster) { onMeta(meta.copy(poster = it)) }
                        }
                    }
                    ColorLine(stringResource(R.string.dynsticker_builder_background), scene.background?.color) {
                        onColor(ColorTarget(null, ColorField.BACKGROUND))
                    }
                    scene.background?.let { background ->
                        ColorLine(stringResource(R.string.dynsticker_builder_background2), background.color2) {
                            onColor(ColorTarget(null, ColorField.BACKGROUND2))
                        }
                    }
                }
            }
        }
    }
}

/** A template that is a document only: its rules, a preview, and its JSON to edit. */
@Composable
private fun DocumentTemplateEditor(start: BuilderStart, store: DynStickerStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var meta by remember { mutableStateOf(start.meta) }
    var document by remember { mutableStateOf(start.document) }
    // Empty: the preview shows the template's own words until something is typed here.
    var testText by remember { mutableStateOf("") }
    var editingJson by remember { mutableStateOf(false) }

    val template = remember(document, meta) {
        document?.let { doc ->
            val slots = meta.slots.ifEmpty { TemplateProps.slots(doc, TemplateProps.read(doc)) }
            DynTemplate(start.previewPack, meta, slots, PackSource.USER, meta.match.phrases.map(DynText::normalize))
        }
    }

    fun save() {
        val doc = document ?: return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val pack = start.ownPack ?: myTemplatesPack(context, store) ?: return@withContext null
                val id = if (start.ownPack != null && meta.id.isNotBlank()) meta.id else freshId(meta.name)
                store.saveTemplate(pack, meta.copy(id = id, file = "", scene = null), LottieDoc.encode(doc), null)
            }
            Toast.makeText(
                context,
                if (saved != null) R.string.dynsticker_builder_saved else R.string.dynsticker_builder_save_failed,
                Toast.LENGTH_SHORT,
            ).show()
            if (saved != null) onBack()
        }
    }

    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val doc = document
                if (template != null && doc != null) {
                    SceneCanvas(
                        document = doc,
                        template = template,
                        text = testText,
                        playing = true,
                        scene = DynScene(width = LottieDoc.info(doc).width, height = LottieDoc.info(doc).height),
                        selected = null,
                        onSelect = {},
                        onMove = { _, _ -> },
                    )
                }
                Text(stringResource(R.string.dynsticker_builder_document_note), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dynsticker_test_text_label)) },
                    placeholder = { Text(stringResource(R.string.dynsticker_test_text_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = ::save, enabled = document != null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (start.ownPack != null) R.string.dynsticker_builder_save else R.string.dynsticker_builder_save_copy))
                }
            }
        }
    }
    TemplateRules(meta = meta, onMeta = { meta = it }, scene = null, onScene = {}, onColor = {})
    SettingsGroup(stringResource(R.string.dynsticker_builder_json_title)) {
        item {
            NavRow(title = R.string.dynsticker_builder_json_copy) { copyText(context, document?.let(LottieDoc::encode).orEmpty()) }
        }
        item {
            NavRow(title = R.string.dynsticker_builder_json_edit) { editingJson = true }
        }
    }
    if (editingJson) {
        JsonEditorDialog(
            initial = document?.let(LottieDoc::encode).orEmpty(),
            detaches = false,
            onDismiss = { editingJson = false },
            onUse = { text ->
                editingJson = false
                val parsed = LottieDoc.parse(text)
                if (parsed == null) {
                    Toast.makeText(context, R.string.dynsticker_builder_json_invalid, Toast.LENGTH_LONG).show()
                } else {
                    document = parsed
                    meta = meta.copy(slots = emptyList(), photos = emptyList())
                }
            },
        )
    }
}

/**
 * The template's Lottie JSON in the app's code editor, full screen: coloured,
 * indented, bracket-matched, with its own undo. [detaches] warns that a scene
 * saved as JSON leaves its layers behind.
 */
@Composable
private fun JsonEditorDialog(initial: String, detaches: Boolean, onDismiss: () -> Unit, onUse: (String) -> Unit) {
    // Indented for reading: the stored document is one line.
    val editor = rememberCodeEditorState(initial, keepHistory = true) { JsonCode.format(initial) ?: initial }
    val colors = rememberCodeColors()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_cancel))
                    }
                    Text(
                        stringResource(R.string.dynsticker_builder_json_edit),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { editor.undo() }, enabled = editor.canUndo) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(CommonR.string.common_undo))
                    }
                    IconButton(onClick = { editor.redo() }, enabled = editor.canRedo) {
                        Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = stringResource(R.string.code_redo_desc))
                    }
                    TextButton(onClick = { onUse(editor.text) }) { Text(stringResource(R.string.dynsticker_builder_json_use)) }
                }
                if (detaches) {
                    Text(
                        stringResource(R.string.dynsticker_builder_json_detach_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                CodeSurface(
                    state = editor,
                    language = JsonCode,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                    colors = colors,
                )
            }
        }
    }
}

// ---- small editing parts ---------------------------------------------------

/**
 * Where the still is taken, picked by looking: a row of the sticker's frames
 * across its length, the chosen one framed. The last is "the end", stored as
 * -1 so it stays the last frame when the length changes.
 */
@Composable
private fun PosterPicker(document: JsonObject, template: DynTemplate, poster: Float, onPick: (Float) -> Unit) {
    val context = LocalContext.current
    val frames by produceState<List<ImageBitmap>?>(null, document, template) {
        // After a pause: a drag or a typed word makes a new document many times a second.
        delay(POSTER_PAUSE_MS)
        value = withContext(Dispatchers.Default) {
            runCatching {
                DynAppRender.renderer(context).prepareDocument(document, template, DynFill(emptyMap()))?.use { prepared ->
                    POSTER_STOPS.map { prepared.render(it, POSTER_THUMB_PX).asImageBitmap() }
                }
            }.getOrNull()
        }
    }
    val chosen = if (poster < 0f) POSTER_STOPS.lastIndex else POSTER_STOPS.indices.minBy { kotlin.math.abs(POSTER_STOPS[it] - poster) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dynsticker_builder_poster), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                percent(if (poster < 0f) 1f else poster),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val row = rememberCentredRow()
        Row(modifier = row.viewport.horizontalScroll(row.scroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            POSTER_STOPS.forEachIndexed { index, stop ->
                val on = index == chosen
                val shape = RoundedCornerShape(8.dp)
                Box(
                    modifier = (if (on) row.selected else Modifier)
                        .size(POSTER_THUMB_SIZE)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            if (on) 3.dp else 1.dp,
                            if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape,
                        )
                        .clickable { onPick(if (index == POSTER_STOPS.lastIndex) -1f else stop) },
                    contentAlignment = Alignment.Center,
                ) {
                    frames?.getOrNull(index)?.let {
                        Image(it, contentDescription = percent(stop), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

private val POSTER_STOPS = List(11) { it / 10f }
private val POSTER_THUMB_SIZE = 56.dp
private const val POSTER_THUMB_PX = 112
private const val POSTER_PAUSE_MS = 300L

@Composable
private fun FieldBlock(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun SliderLine(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChipLine(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        // Opens with the chosen chip in the middle: a long row (fonts, art)
        // would otherwise open at its start with the choice out of sight.
        val row = rememberCentredRow()
        Row(
            modifier = row.viewport.horizontalScroll(row.scroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((value, text) in options) {
                val on = value == selected
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = (if (on) row.selected else Modifier).clickable { onSelect(value) },
                ) {
                    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun ColorLine(label: String, color: Long?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (color == null) {
            Text(stringResource(R.string.dynsticker_builder_none), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(color.toInt()))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
    }
}

/** A comma-separated list typed as one line. */
@Composable
private fun ListField(label: String, hint: String, values: List<String>, onChange: (List<String>) -> Unit) {
    // Kept as typed: re-joining the list on every key would eat a comma before its next word.
    var text by remember(label) { mutableStateOf(values.joinToString(", ")) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            onChange(typed.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        },
        label = { Text(label) },
        supportingText = { Text(hint) },
        modifier = Modifier.fillMaxWidth(),
    )
}

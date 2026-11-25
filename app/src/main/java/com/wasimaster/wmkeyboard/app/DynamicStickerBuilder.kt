package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
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
import com.wasimaster.wmkeyboard.ime.dynstickers.DynRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.math.roundToInt

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

@Composable
private fun SceneBuilder(start: BuilderStart, store: DynStickerStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scene by remember { mutableStateOf(checkNotNull(start.scene)) }
    var meta by remember { mutableStateOf(start.meta) }
    var selected by remember { mutableStateOf(scene.elements.lastOrNull { it.slot != null }?.id ?: scene.elements.lastOrNull()?.id) }
    var testText by remember { mutableStateOf(context.getString(R.string.dynsticker_test_text_default)) }
    var playing by remember { mutableStateOf(true) }
    var previewPack by remember { mutableStateOf(start.previewPack) }
    var ownPack by remember { mutableStateOf(start.ownPack) }
    var picking by remember { mutableStateOf<ColorTarget?>(null) }
    var editingJson by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var nextId by remember { mutableIntStateOf((scene.elements.mapNotNull { it.id.removePrefix("e").toIntOrNull() }.maxOrNull() ?: 0) + 1) }

    val compiled = remember(scene, meta) {
        runCatching { SceneToLottie.compile(scene, meta.name, meta.match, meta.altText, store::artFor) }.getOrNull()
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

    fun update(id: String, change: (SceneElement) -> SceneElement) {
        scene = scene.copy(elements = scene.elements.map { if (it.id == id) change(it) else it })
    }

    fun add(element: SceneElement) {
        scene = scene.copy(elements = scene.elements + element)
        selected = element.id
        nextId++
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
                Toast.makeText(context, R.string.dynsticker_builder_saved, Toast.LENGTH_SHORT).show()
                onBack()
            }
        }
    }

    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (template != null && compiled != null) {
                    SceneCanvas(
                        document = compiled.document,
                        template = template,
                        text = testText,
                        playing = playing,
                        scene = scene,
                        selected = selected,
                        onSelect = { selected = it },
                        onMove = { dx, dy -> selected?.let { id -> update(id) { e -> e.copy(x = (e.x + dx).coerceIn(-0.2f, 1.2f), y = (e.y + dy).coerceIn(-0.2f, 1.2f)) } } },
                    )
                } else {
                    Text(stringResource(R.string.dynsticker_builder_compile_failed), color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = testText,
                        onValueChange = { testText = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.dynsticker_test_text_label)) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { playing = !playing }) {
                        Text(stringResource(if (playing) R.string.dynsticker_builder_pause else R.string.dynsticker_builder_play))
                    }
                }
                Button(onClick = ::save, enabled = !saving && compiled != null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (start.ownPack != null) R.string.dynsticker_builder_save else R.string.dynsticker_builder_save_copy))
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.dynsticker_builder_elements_title)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = {
                    val taken = scene.elements.mapNotNull { it.slot }.toSet()
                    val (slot, role) = if (TemplateProps.MAIN_SLOT !in taken) TemplateProps.MAIN_SLOT to SlotRole.MAIN else "label${nextId}" to SlotRole.LABEL
                    add(SceneElement(id = "e$nextId", type = ElementType.TEXT, slot = slot, role = role, text = testText, h = 0.25f, stroke = 0xFF000000L, strokeWidth = 6f))
                }) { Text(stringResource(R.string.dynsticker_builder_add_text)) }
                OutlinedButton(onClick = {
                    add(SceneElement(id = "e$nextId", type = ElementType.TEXT, text = context.getString(R.string.dynsticker_builder_words_sample), y = 0.2f, h = 0.15f, case = TextCase.KEEP))
                }) { Text(stringResource(R.string.dynsticker_builder_add_words)) }
                OutlinedButton(onClick = {
                    add(SceneElement(id = "e$nextId", type = ElementType.SHAPE, shape = ShapeKind.STAR, w = 0.3f, h = 0.3f, fill = 0xFFFFD60AL, x = 0.8f, y = 0.2f))
                }) { Text(stringResource(R.string.dynsticker_builder_add_shape)) }
                val art = remember { store.artIds() }
                if (art.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        add(SceneElement(id = "e$nextId", type = ElementType.ART, art = art.first(), w = 0.35f, h = 0.35f, x = 0.2f, y = 0.2f))
                    }) { Text(stringResource(R.string.dynsticker_builder_add_art)) }
                }
                OutlinedButton(onClick = { pickImage.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.dynsticker_builder_add_image)) }
                OutlinedButton(onClick = {
                    val n = scene.elements.count { it.type == ElementType.PHOTO } + 1
                    add(SceneElement(id = "e$nextId", type = ElementType.PHOTO, slot = "photo$n", w = 0.9f, h = 0.6f, label = context.getString(R.string.dynsticker_builder_photo_label)))
                    meta = meta.copy(kind = DynKind.MEME)
                }) { Text(stringResource(R.string.dynsticker_builder_add_photo)) }
            }
        }
        // Topmost first, which is how a layer list reads.
        for (element in scene.elements.asReversed()) {
            item(key = element.id) {
                ElementRow(
                    element = element,
                    selected = element.id == selected,
                    onSelect = { selected = element.id },
                    onUp = {
                        val list = scene.elements.toMutableList()
                        val at = list.indexOfFirst { it.id == element.id }
                        if (at < list.lastIndex) {
                            list.add(at + 1, list.removeAt(at))
                            scene = scene.copy(elements = list)
                        }
                    },
                    onDown = {
                        val list = scene.elements.toMutableList()
                        val at = list.indexOfFirst { it.id == element.id }
                        if (at > 0) {
                            list.add(at - 1, list.removeAt(at))
                            scene = scene.copy(elements = list)
                        }
                    },
                    onDuplicate = { add(element.copy(id = "e$nextId", x = (element.x + 0.05f).coerceAtMost(1f), y = (element.y + 0.05f).coerceAtMost(1f))) },
                    onDelete = {
                        scene = scene.copy(elements = scene.elements.filter { it.id != element.id })
                        if (selected == element.id) selected = scene.elements.lastOrNull()?.id
                    },
                )
            }
        }
    }

    val current = scene.elements.firstOrNull { it.id == selected }
    if (current != null) {
        ElementEditor(
            element = current,
            frames = scene.frames,
            artIds = remember { store.artIds() },
            onChange = { change -> update(current.id, change) },
            onColor = { picking = it },
        )
    }

    TemplateRules(
        meta = meta,
        onMeta = { meta = it },
        scene = scene,
        onScene = { scene = it },
        onColor = { picking = it },
    )

    SettingsGroup(stringResource(R.string.dynsticker_builder_json_title), info = stringResource(R.string.dynsticker_builder_json_info)) {
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
                applyColor(target, argb, scene)?.let { scene = it }
            },
            onReset = {
                picking = null
                applyColor(target, null, scene)?.let { scene = it }
            },
            onDismiss = { picking = null },
        )
    }
    if (editingJson) {
        JsonDialog(
            initial = compiled?.let { LottieDoc.encode(it.document) }.orEmpty(),
            detaches = true,
            onDismiss = { editingJson = false },
            onUse = { text ->
                editingJson = false
                val doc = LottieDoc.parse(text)
                if (doc == null) {
                    Toast.makeText(context, R.string.dynsticker_builder_json_invalid, Toast.LENGTH_LONG).show()
                } else {
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

private fun freshId(name: String): String {
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifBlank { "t" }
    return slug + "_" + UUID.randomUUID().toString().take(4)
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Lottie", text))
    Toast.makeText(context, R.string.dynsticker_builder_json_copied, Toast.LENGTH_SHORT).show()
}

/** The preview: drawn by the keyboard's own renderer, playing, with the selected element framed and draggable. */
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
    LaunchedEffect(prepared, playing) {
        val p = prepared ?: return@LaunchedEffect
        if (!playing || !p.animated) {
            progress = p.posterProgress
            return@LaunchedEffect
        }
        val length = p.info.durationMs.coerceAtLeast(1).toLong()
        val began = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            progress = ((now - began) % length) / length.toFloat()
            delay(FRAME_MS)
        }
    }
    LaunchedEffect(prepared, progress) {
        val p = prepared ?: return@LaunchedEffect
        val index = frameNo % 2
        val target = buffers[index] ?: Bitmap.createBitmap(PREVIEW_PX, PREVIEW_PX, Bitmap.Config.ARGB_8888).also { buffers[index] = it }
        withContext(Dispatchers.Default) { lock.withLock { runCatching { p.draw(progress, target) } } }
        shown = target
        frameNo++
    }
    val outline = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .aspectRatio(scene.width.toFloat() / scene.height.coerceAtLeast(1))
            .clip(RoundedCornerShape(12.dp))
            .background(Checker)
            .pointerInput(scene.elements, selected) {
                detectTapGestures { offset ->
                    val fx = offset.x / size.width
                    val fy = offset.y / size.height
                    val hit = scene.elements.lastOrNull { e ->
                        fx in (e.x - e.w / 2)..(e.x + e.w / 2) && fy in (e.y - e.h / 2)..(e.y + e.h / 2)
                    }
                    onSelect(hit?.id)
                }
            }
            .pointerInput(selected) {
                detectDragGestures { change, amount ->
                    change.consume()
                    onMove(amount.x / size.width, amount.y / size.height)
                }
            },
    ) {
        // Recomposed by frameNo, which moves with every drawn frame.
        frameNo.let { _ ->
            shown?.let { Image(it.asImageBitmap(), contentDescription = template.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        scene.elements.firstOrNull { it.id == selected }?.let { e ->
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .padding(start = maxWidth * (e.x - e.w / 2).coerceIn(0f, 1f), top = maxHeight * (e.y - e.h / 2).coerceIn(0f, 1f))
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

@Composable
private fun ElementRow(
    element: SceneElement,
    selected: Boolean,
    onSelect: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val label = when (element.type) {
        ElementType.TEXT -> if (element.slot != null) {
            stringResource(R.string.dynsticker_builder_el_slot, element.text.ifBlank { element.slot.orEmpty() })
        } else {
            stringResource(R.string.dynsticker_builder_el_words, element.text)
        }
        ElementType.SHAPE -> stringResource(R.string.dynsticker_builder_el_shape, element.shape?.name?.lowercase().orEmpty())
        ElementType.ART -> stringResource(R.string.dynsticker_builder_el_art, element.art.orEmpty())
        ElementType.IMAGE -> stringResource(R.string.dynsticker_builder_el_image)
        ElementType.PHOTO -> stringResource(R.string.dynsticker_builder_el_photo, element.label.ifBlank { element.slot.orEmpty() })
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = onUp) { Text("↑") }
        TextButton(onClick = onDown) { Text("↓") }
        TextButton(onClick = onDuplicate) { Text(stringResource(R.string.dynsticker_builder_copy_short)) }
        TextButton(onClick = onDelete) { Text(stringResource(CommonR.string.common_delete)) }
    }
}

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
    SettingsGroup(stringResource(R.string.dynsticker_builder_selected_title)) {
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
    SettingsGroup(stringResource(R.string.dynsticker_builder_motion_title)) {
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

private fun frameLabel(frames: Float): String = "${frames.roundToInt()}f"

@Composable
private fun presetLabel(preset: AnimPreset): String = preset.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

/** What a template is offered for, how long it runs, and what it is drawn on. */
@Composable
private fun TemplateRules(
    meta: TemplateMeta,
    onMeta: (TemplateMeta) -> Unit,
    scene: DynScene?,
    onScene: (DynScene) -> Unit,
    onColor: (ColorTarget) -> Unit,
) {
    SettingsGroup(stringResource(R.string.dynsticker_builder_rules_title)) {
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
                            val seconds = scene.frames / scene.fps.coerceAtLeast(1).toFloat()
                            onScene(scene.copy(fps = fps, frames = (seconds * fps).roundToInt().coerceAtLeast(2)))
                        }
                        SwitchLine(stringResource(R.string.dynsticker_builder_still_only), meta.stillOnly) { onMeta(meta.copy(stillOnly = it)) }
                    }
                    SliderLine(
                        stringResource(R.string.dynsticker_builder_poster),
                        if (meta.poster < 0f) 1f else meta.poster, 0f..1f,
                        format = ::percent,
                    ) { onMeta(meta.copy(poster = if (it >= 0.995f) -1f else it)) }
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
    var testText by remember { mutableStateOf(context.getString(R.string.dynsticker_test_text_default)) }
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
        JsonDialog(
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

@Composable
private fun JsonDialog(initial: String, detaches: Boolean, onDismiss: () -> Unit, onUse: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dynsticker_builder_json_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detaches) Text(stringResource(R.string.dynsticker_builder_json_detach_warning), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onUse(text) }) { Text(stringResource(R.string.dynsticker_builder_json_use)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

// ---- small editing parts ---------------------------------------------------

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
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((value, text) in options) {
                val on = value == selected
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { onSelect(value) },
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

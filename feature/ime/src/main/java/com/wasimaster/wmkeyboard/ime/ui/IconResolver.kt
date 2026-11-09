package com.wasimaster.wmkeyboard.ime.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.wasimaster.wmkeyboard.core.icons.IconArt
import com.wasimaster.wmkeyboard.core.icons.IconLibrary
import com.wasimaster.wmkeyboard.core.icons.IconOverrides
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.RasterIcons
import com.wasimaster.wmkeyboard.core.icons.SvgParser
import com.wasimaster.wmkeyboard.core.icons.SymbolIcons
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.IconSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

/**
 * A resolved icon: what to draw, and whether the caller's tint applies.
 *
 * A monochrome icon declared no colours of its own and is recoloured to match
 * the theme (and the per-tool accent colour). One that did is drawn as its
 * author painted it, which is why the tint has to be dropped for it rather
 * than flattening it to a single colour.
 *
 * Exactly one of [vector] and [bitmap] is set. A pack's icon may be either
 * since issue #504 — a vector where the author drew one, a raster where they
 * had a PNG, or where it came out of a Gboard theme, which is the only form
 * those ship their key glyphs in.
 */
@Immutable
data class ResolvedIcon(
    val vector: ImageVector? = null,
    val bitmap: ImageBitmap? = null,
    val monochrome: Boolean = true,
)

/**
 * Every icon the user has replaced, already parsed and ready to draw.
 *
 * Built off the settings and the installed packs on a background thread and
 * then held immutable, because the alternative — resolving on demand — would
 * put an SVG parse and a file read on the keyboard's render path. A slot that
 * is not in here draws its built-in glyph, so an empty set is exactly the
 * stock keyboard.
 */
@Immutable
class IconSet(private val icons: Map<String, ResolvedIcon>) {

    fun resolve(slot: String): ResolvedIcon? = icons[slot]

    val size: Int get() = icons.size

    companion object {
        /** Nothing replaced: every slot falls through to [IconDefaults]. */
        val Builtin = IconSet(emptyMap())
    }
}

/**
 * The icon set in force for this subtree.
 *
 * Defaults to [IconSet.Builtin] so a composable that is rendered outside a
 * provider — a preview, a dialog hosted elsewhere — still draws real icons
 * instead of blanks.
 */
val LocalIconSet = staticCompositionLocalOf { IconSet.Builtin }

/**
 * Whether the layout in use is a phonetic one for an Indic language.
 *
 * The phonetic English switch wears the plain translate glyph, which the
 * Translate tool wears too. On an Indic phonetic layout it draws
 * `translate_indic` (a Devanagari letter beside a Latin one) instead, which says
 * what the switch flips between. Arabic-script phonetic layouts keep the plain
 * glyph: its non-Latin half is a CJK character, no closer to theirs.
 *
 * Not static: it changes with the language, and only the one slot reads it.
 */
val LocalPhoneticIndic = compositionLocalOf { false }

/** The phonetic languages typed in an Arabic script; every other one is Indic. */
private val ArabicScriptPhonetic = setOf("ar", "fa", "ur")

/** Whether [phoneticLanguage], a composer's `phoneticLanguage`, is an Indic one. */
fun phoneticIsIndic(phoneticLanguage: String?): Boolean =
    phoneticLanguage != null && phoneticLanguage !in ArabicScriptPhonetic

private val PhoneticEnglishSlot = IconSlots.forTool(ToolbarTool.PHONETIC_ENGLISH)

/**
 * Draws the icon for [slot]: the user's replacement if they set one, otherwise
 * the built-in glyph.
 *
 * A drop-in replacement for `Icon(…)` at every customisable call site. Full
 * colour is handled by passing `Color.Unspecified` as the tint, which is
 * Material's own "no colour filter" signal — so one code path covers both
 * kinds of icon and the caller's sizing modifier keeps working unchanged.
 *
 * [brush] paints the glyph with a gradient instead of a flat [tint]. Material's
 * `Icon` only knows how to tint, so the glyph is drawn into its own layer and
 * the brush is stamped through it — see [Modifier.paintedWith]. A full-colour
 * icon from a pack keeps its own colours either way: recolouring it is what the
 * monochrome flag exists to prevent.
 */
@Composable
fun SlotIcon(
    slot: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    brush: Brush? = null,
) {
    val resolved = LocalIconSet.current.resolve(slot)
    val recolourable = resolved == null || resolved.monochrome
    val painted = if (brush != null && recolourable) modifier.paintedWith(brush) else modifier
    val shade = if (recolourable) tint else Color.Unspecified
    val bitmap = resolved?.bitmap
    if (bitmap != null) {
        Icon(bitmap = bitmap, contentDescription = contentDescription, modifier = painted, tint = shade)
        return
    }
    val vector = resolved?.vector ?: builtinIcon(slot) ?: return
    Icon(imageVector = vector, contentDescription = contentDescription, modifier = painted, tint = shade)
}

/** The built-in glyph for [slot], with the one that depends on the language. */
@Composable
private fun builtinIcon(slot: String): ImageVector? =
    // The slot is compared first so that every other icon on the board stays
    // clear of the composition local and never recomposes when it changes.
    if (slot == PhoneticEnglishSlot && LocalPhoneticIndic.current) {
        SymbolIcons.TranslateIndic
    } else {
        IconDefaults.forSlot(slot)
    }

/**
 * Replaces whatever this element drew with [brush], keeping its shape.
 *
 * The element is rendered into an offscreen layer and the brush is then drawn
 * over it in `SrcIn`, which keeps the brush only where the glyph put pixels.
 * That is the standard way to give a vector `Icon` a gradient: `tint` is a
 * single colour by construction, and a `ColorFilter` cannot vary across the
 * icon either.
 */
fun Modifier.paintedWith(brush: Brush): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(brush, blendMode = BlendMode.SrcIn)
    }

/**
 * Resolves [settings] against the installed packs, off the main thread.
 *
 * Reading and parsing a full pack is tens of milliseconds — small, but not
 * something to do during composition — so the set starts as [IconSet.Builtin]
 * and swaps in when it is ready. The keyboard therefore draws stock icons for
 * the first frame or two after a cold start, which is the same trade the
 * dictionaries and the emoji catalog already make.
 */
@Composable
fun rememberIconSet(settings: IconSettings): State<IconSet> {
    val context = LocalContext.current
    val store = remember(context) { IconPackStore.get(context) }
    val library = remember(context) { IconLibrary.get(context) }
    // The store's revision changes whenever a pack is added, edited or removed,
    // so an import shows up without anyone having to signal the keyboard. It is
    // an in-memory counter rather than the manifest's mtime on purpose: this
    // runs on every recomposition, and the keyboard recomposes on every
    // keystroke, so a file stat here would be a syscall per key. The library's
    // counter joins it: a picture renamed, recoloured or deleted redraws too.
    val revision by store.revision.collectAsState()
    val libraryRevision by library.revision.collectAsState()
    return produceState(IconSet.Builtin, settings, revision, libraryRevision) {
        value = withContext(Dispatchers.Default) { buildIconSet(settings, store, library) }
    }
}

/**
 * The blocking half of [rememberIconSet]: reads and parses whatever the
 * settings point at. Safe to call from any thread but the main one.
 */
fun buildIconSet(settings: IconSettings, store: IconPackStore, library: IconLibrary? = null): IconSet {
    // Before the early return, not after: a user with no icons customised is
    // exactly the one whose first frame would otherwise pay for building them.
    IconDefaults.warm()

    val activePackId = settings.activePackId
    val themeIcons = settings.themeIcons
    if (activePackId.isEmpty() && settings.overrides.isEmpty() && themeIcons.isEmpty()) {
        return IconSet.Builtin
    }

    val resolved = HashMap<String, ResolvedIcon>()
    // The theme's own icons are the weakest layer: they dress a board the user
    // picked, but the icon pack and the per-slot picks are standing choices
    // about the whole keyboard, and a theme must not quietly undo one.
    for ((slot, path) in themeIcons) {
        readIconFile(path)?.toResolvedIcon(slot, fitGlyph = true)?.let { resolved[slot] = it }
    }
    // Then the active pack, so a per-slot override written afterwards wins.
    // Its variants (`tool.gif@text`) are skipped here: they are only drawn for
    // a slot whose override picks one, below, and building the rest would be
    // a parse or a bitmap decode per variant for nothing.
    val packArt = if (activePackId.isNotEmpty()) store.art(activePackId) else emptyMap()
    for ((key, art) in packArt) {
        if (IconSlots.variantOf(key) != null) continue
        resolved[key] = art.toResolvedIcon(key) ?: continue
    }
    for ((slot, source) in settings.overrides) {
        val variant = IconOverrides.variantOf(source)
        if (variant != null) {
            // A variant is relative to whatever supplies the slot. The active
            // pack's own variant wins; a pack that draws this slot but not
            // that variant keeps its own glyph, so one built-in look does not
            // turn up among the pack's; and a slot no pack covers takes the
            // app's variant of that name.
            val fromPack = packArt[IconSlots.variantKey(slot, variant)]?.toResolvedIcon(slot)
            val icon = when {
                fromPack != null -> fromPack
                slot in packArt -> null
                else -> IconDefaults.variant(slot, variant)?.let { ResolvedIcon(vector = it, monochrome = true) }
            }
            if (icon != null) resolved[slot] = icon
            continue
        }
        val icon = when {
            source.startsWith(IconOverrides.BUILTIN_PREFIX) ->
                BuiltinIcons.byName(source.removePrefix(IconOverrides.BUILTIN_PREFIX))
                ?.let { ResolvedIcon(vector = it, monochrome = true) }
            source.startsWith(IconOverrides.PACK_PREFIX) ->
                store.art(source.removePrefix(IconOverrides.PACK_PREFIX))[slot]
                ?.toResolvedIcon(slot)
            source.startsWith(IconOverrides.LIBRARY_PREFIX) ->
                library?.let { resolveLibraryIcon(it, source.removePrefix(IconOverrides.LIBRARY_PREFIX), slot) }
            else -> null
        }
        // A source naming a pack that was uninstalled, or an icon renamed out
        // of the catalog, leaves the slot on whatever the pack or the built-in
        // gives it rather than drawing nothing.
        if (icon != null) resolved[slot] = icon else resolved.remove(slot)
    }
    return if (resolved.isEmpty()) IconSet.Builtin else IconSet(resolved)
}

/**
 * One icon out of [packId], ready to draw: a slot's own (`tool.gif`) or one of
 * its variants (`tool.gif@text`). Null when the pack has no such icon or it
 * will not build.
 *
 * Blocking on the first read of a pack, like [IconPackStore.art] underneath it,
 * so the settings app's variant row calls it off the main thread.
 */
fun resolvePackIcon(store: IconPackStore, packId: String, key: String): ResolvedIcon? =
    store.art(packId)[key]?.toResolvedIcon(IconSlots.slotOf(key))

/**
 * A picture out of the user's [IconLibrary], ready to draw, with the entry's own
 * colour choice laid over the guess. Null when it was deleted or will not build,
 * which leaves the slot or key to its default.
 *
 * Blocking on the first read of each picture, like [IconLibrary.art], so the
 * pickers call it off the main thread. [slot] only names the drawing for
 * Compose's vector builder; a layout's key passes its own icon name.
 */
fun resolveLibraryIcon(library: IconLibrary, id: String, slot: String = id): ResolvedIcon? {
    val entry = library.icon(id) ?: return null
    val art = library.art(id) ?: return null
    val built = art.toResolvedIcon(slot) ?: return null
    return built.copy(monochrome = IconLibrary.monochromeOf(entry, built.monochromeAs(art)))
}

/**
 * The art with a raster's pixel verdict filled in from the decoded bitmap, so
 * [IconLibrary.monochromeOf] decides on the same answer the keyboard would draw
 * with when the user has left the choice to the picture.
 */
private fun ResolvedIcon.monochromeAs(art: IconArt): IconArt =
    if (art is IconArt.Raster && art.mask == null) art.withMask(monochrome) else art

/**
 * A library picture for one composable, resolved off the main thread and
 * redrawn when the library changes. Null while it loads, and for an id the
 * library does not have — the caller falls back the way it does for an unknown
 * icon name.
 */
@Composable
fun rememberLibraryIcon(id: String?): ResolvedIcon? {
    if (id == null) return null
    val context = LocalContext.current
    val library = remember(context) { IconLibrary.get(context) }
    val revision by library.revision.collectAsState()
    val icon by produceState<ResolvedIcon?>(null, id, revision) {
        value = withContext(Dispatchers.Default) { resolveLibraryIcon(library, id) }
    }
    return icon
}

/**
 * A theme's icon file, ready to draw the way the keyboard draws it, for the
 * theme editor's Key icons rows. Null while it loads and for a file that has
 * gone.
 */
@Composable
fun rememberIconFile(path: String?): ResolvedIcon? {
    val icon by produceState<ResolvedIcon?>(null, path) {
        value = path?.let {
            withContext(Dispatchers.IO) { readIconFile(it)?.toResolvedIcon(it, fitGlyph = true) }
        }
    }
    return icon
}

/**
 * A theme's icon file as art, or null when it has gone or is not one.
 *
 * A theme points at app-private files `withExtractedImages` wrote, so the path
 * is ours; the caps still apply, because the theme itself may have come from a
 * stranger. Bounded the same way a pack's own file is — see
 * `IconPackStore.readArt`, which this deliberately mirrors.
 */
private fun readIconFile(path: String): IconArt? {
    val file = File(path)
    if (!file.isFile || file.length() > RasterIcons.MAX_SOURCE_BYTES) return null
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    RasterIcons.read(bytes)?.let { return it }
    if (bytes.size > SvgParser.MAX_SOURCE_BYTES) return null
    return runCatching { SvgParser.parse(bytes.decodeToString()) }.getOrNull()?.let(IconArt::Vector)
}

/**
 * Builds the drawable form of an icon, or null when it cannot be built.
 *
 * The readers are lenient by design and the drawing side is not: a document the
 * SVG parser accepted can still fail to become an [ImageVector] — unusable path
 * data, geometry that overflows, a shape Compose's builder rejects — and a
 * raster whose header read fine can still fail to decode. Every one of those is
 * a stranger's file, and none of them is worth taking the keyboard down for. A
 * null here leaves the slot to [IconDefaults], so the failure shows up as "this
 * one icon didn't change" instead of a crash on the frame that first drew it.
 */
private fun IconArt.toResolvedIcon(slot: String, fitGlyph: Boolean = false): ResolvedIcon? = when (this) {
    is IconArt.Vector ->
        runCatching { ResolvedIcon(vector = doc.toImageVector(slot), monochrome = doc.monochrome) }
            .getOrNull()

    is IconArt.Raster -> runCatching {
        val decoded = decodeIcon(bytes) ?: return null
        val bitmap = if (fitGlyph) decoded.fittedLikeMaterial() else decoded
        // The pixel verdict, which the header could not give: a glyph drawn in
        // one colour on transparency is a mask and takes the theme's tint, and
        // anything with more than one colour in it was painted deliberately.
        // Gboard themes are the reason this matters — three quarters of the
        // glyphs in the Rboard repository are white-on-transparent, and drawn
        // as authored they vanish on a light theme.
        ResolvedIcon(bitmap = bitmap.asImageBitmap(), monochrome = mask ?: bitmap.looksLikeMask())
    }.getOrNull()
}

/**
 * Decodes an icon no larger than a key can show it.
 *
 * `inSampleSize` halves, so a glyph at the size themes actually ship (around
 * 100 px) is decoded untouched and only something far larger is reduced. The
 * cap is there because a pack holds ninety slots and an ARGB bitmap is four
 * bytes a pixel: at 2048 px square that is 16 MB per icon, in a process whose
 * whole budget the keyboard shares with the app (#476).
 */
private fun decodeIcon(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest / (sample * 2) >= MAX_ICON_PX) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/**
 * Whether every visible pixel is the same hue, so the image is a stencil of a
 * glyph rather than a picture of one.
 *
 * Sampled on a stride rather than read whole: the answer only has to be right
 * about artwork, and a grid of a few thousand pixels settles that. Anti-aliased
 * edges are why the test is on the opaque-enough pixels and not all of them —
 * a white glyph's fringe runs through every alpha, and its *colour* stays
 * white.
 */
private fun Bitmap.looksLikeMask(): Boolean {
    if (width <= 0 || height <= 0) return false
    val stride = maxOf(1, maxOf(width, height) / MASK_SAMPLES)
    var seen = -1
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = getPixel(x, y)
            if ((pixel ushr 24) and 0xFF >= MASK_MIN_ALPHA) {
                val rgb = pixel and 0xFFFFFF
                if (seen == -1) seen = rgb else if (seen != rgb) return false
            }
            x += stride
        }
        y += stride
    }
    // Nothing visible at all is not a mask; it is an empty file, and drawing it
    // tinted would put a solid block of the theme's colour on the key.
    return seen != -1
}

/**
 * This glyph trimmed to what it draws and centred in a square with the margin a
 * Material icon leaves, so it comes out the size of the icon it replaces.
 *
 * A Gboard theme's key glyphs are PNGs cropped tight to their edges, or nearly,
 * and a Material icon's glyph covers about three quarters of its 24 dp box. Drawn
 * into the same box, the theme's delete and enter came out a third bigger than
 * the stock ones beside them. Trimming first makes the result the same whatever
 * margin the file happened to carry.
 */
private fun Bitmap.fittedLikeMaterial(): Bitmap {
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    val row = IntArray(width)
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            if ((row[x] ushr 24) > TRIM_MAX_ALPHA) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
    }
    // Nothing visible: leave it to the mask test, which refuses an empty file.
    if (right < 0) return this
    val glyphWidth = right - left + 1
    val glyphHeight = bottom - top + 1
    val side = ceil(maxOf(glyphWidth, glyphHeight) / MATERIAL_GLYPH_SHARE).toInt()
    val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val x = (side - glyphWidth) / 2
    val y = (side - glyphHeight) / 2
    Canvas(out).drawBitmap(
        this,
        Rect(left, top, right + 1, bottom + 1),
        Rect(x, y, x + glyphWidth, y + glyphHeight),
        Paint(Paint.FILTER_BITMAP_FLAG),
    )
    return out
}

/** How much of its box a Material icon's glyph covers along its longer side. */
private const val MATERIAL_GLYPH_SHARE = 0.75f

/** At or below this alpha a pixel is margin, not glyph: the faintest anti-aliasing. */
private const val TRIM_MAX_ALPHA = 0x08

/** Longest edge an icon is decoded at; see [decodeIcon]. */
private const val MAX_ICON_PX = 192

/** Roughly how many samples across the longest edge [looksLikeMask] takes. */
private const val MASK_SAMPLES = 48

/** Below this an edge pixel's colour is the background showing through. */
private const val MASK_MIN_ALPHA = 0x80

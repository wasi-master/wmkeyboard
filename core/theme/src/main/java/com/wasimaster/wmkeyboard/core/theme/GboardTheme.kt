package com.wasimaster.wmkeyboard.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reading a Gboard theme ZIP, and the Rboard theme packs made of them.
 *
 * A Gboard theme is a ZIP with a `metadata.json` naming its stylesheets, the
 * stylesheets themselves (see [GboardCss]), and any images they point at. It is
 * what Gboard exports, what it imports, and what the Rboard community has
 * published several hundred of. An Rboard *pack* is one level up: a ZIP holding
 * theme ZIPs, an optional `pack.meta` (`name=` / `author=` lines) and, for each
 * theme, an extensionless preview picture under the theme's own name.
 *
 * **Written from the files, not from any Gboard or Rboard source.** The format
 * is not published; what is here was read off the themes in the Rboard
 * repository, and the tests build their own archives.
 *
 * ### What a theme carries that this keyboard has no field for
 *
 * The colours of the keys, the board, the bar above them, the enter key and the
 * popups come across, and so do the board photo (portrait and landscape), key
 * outlines, corner size and lift. Gboard's key icons, its per-key padding, its
 * fonts, rounded board corners and the styling of Gboard features this keyboard
 * does not have do not. Each loss that a user would notice is named in
 * [GboardUnsupported]; the rest are in the "N of M style rules" count, which is
 * computed from what the mapper actually read (see [GboardStyle.lands]).
 *
 * ### Key borders are Gboard's switch, and become two looks
 *
 * Gboard draws a theme with or without key borders by a setting of its own, and
 * a theme ships a `BORDER` flavour for the bordered version. Picking one would
 * throw the other away, so both come across as looks of one theme — the one the
 * theme prefers first — and the gallery shows them as one card with two dots.
 *
 * Never throws: a truncated archive, a compiled (`.binarypb`) theme, and a ZIP
 * that is something else entirely are ordinary outcomes with their own answer.
 */
object GboardTheme {

    const val METADATA = "metadata.json"
    private const val METADATA_COMPILED = "metadata.binarypb"
    const val PACK_META = "pack.meta"

    /** What the picker accepts. The real check is the metadata inside. */
    val IMPORT_MIME_TYPES = arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )

    /** The [ConvertedTheme.images] key for the landscape board photo. */
    const val IMAGE_BACKGROUND_LANDSCAPE = "backgroundLandscape"

    // Zip-bomb guards. Sizes come from bytes actually inflated, never from what
    // an entry declares. A pack's budget is larger than a theme's because the
    // biggest pack in the Rboard repository is 8 MB compressed.
    private const val MAX_ENTRIES = 256
    private const val MAX_PACK_ENTRIES = 600
    private const val MAX_THEME_BYTES = 16L * 1024 * 1024
    private const val MAX_PACK_BYTES = 48L * 1024 * 1024
    private const val MAX_TEXT_BYTES = 512 * 1024
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val MAX_INNER_THEME_BYTES = 8 * 1024 * 1024

    /** Past this a pack is not a pack, it is a way to spend the phone's afternoon. */
    const val MAX_PACK_THEMES = 128

    /**
     * Reads [input] as a theme or a pack. [fallbackName] names a single theme
     * whose metadata has none — the file's own name, usually.
     */
    fun read(input: InputStream, fallbackName: String = ""): GboardResult {
        val files = runCatching { unpack(input, MAX_PACK_ENTRIES, MAX_PACK_BYTES, MAX_INNER_THEME_BYTES) }
            .getOrNull()?.let(::rooted) ?: return GboardResult.Unreadable
        if (isTheme(files)) {
            val theme = convert(files, fallbackName, packEntry = false)
                ?: return if (isCompiled(files)) GboardResult.Compiled else GboardResult.NotAGboardTheme
            return GboardResult.Converted(listOf(theme), packName = "", packAuthor = "", skipped = 0)
        }
        if (isCompiled(files)) return GboardResult.Compiled
        return readPack(files)
    }

    /**
     * [files] with a single wrapping folder taken off, when the theme was
     * zipped as a folder (`Dusk/metadata.json`) rather than as its contents.
     * A file manager's "compress" does exactly that.
     */
    private fun rooted(files: Map<String, ByteArray>): Map<String, ByteArray> {
        if (METADATA in files) return files
        val nested = files.keys.filter { it.endsWith("/$METADATA") && it.count { c -> c == '/' } == 1 }
        val prefix = nested.singleOrNull()?.removeSuffix(METADATA) ?: return files
        return files.filterKeys { it.startsWith(prefix) }.mapKeys { (key, _) -> key.removePrefix(prefix) }
    }

    /** Whether [files] is a theme in its own right rather than a pack. */
    private fun isTheme(files: Map<String, ByteArray>): Boolean =
        METADATA in files || files.keys.any { it.isRootCss() }

    /** Nothing readable, and at least one compiled sheet or compiled metadata. */
    private fun isCompiled(files: Map<String, ByteArray>): Boolean =
        files.keys.none { it.isRootCss() } &&
            (METADATA_COMPILED in files || files.keys.any { it.endsWith(".binarypb", ignoreCase = true) })

    private fun String.isRootCss(): Boolean = !contains('/') && endsWith(".css", ignoreCase = true)

    private fun readPack(files: Map<String, ByteArray>): GboardResult {
        val inner = files.keys
            .filter { it.endsWith(".zip", ignoreCase = true) && !it.startsWith("__MACOSX") }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .take(MAX_PACK_THEMES)
        if (inner.isEmpty()) return GboardResult.NotAGboardTheme
        var skipped = 0
        val themes = inner.mapNotNull { entry ->
            val name = entry.substringAfterLast('/').dropLast(".zip".length)
            val theme = runCatching {
                val themeFiles = unpack(ByteArrayInputStream(files.getValue(entry)), MAX_ENTRIES, MAX_THEME_BYTES, MAX_IMAGE_BYTES)
                if (isTheme(themeFiles)) convert(themeFiles, name, packEntry = true) else null
            }.getOrNull()
            if (theme == null) {
                skipped++
                null
            } else {
                // The picture Rboard shows for a theme sits beside it in the
                // pack, named after it with no extension.
                val preview = files[entry.dropLast(".zip".length)]?.takeIf { looksLikeImage(it) }
                theme.copy(preview = preview)
            }
        }
        if (themes.isEmpty()) return if (skipped > 0) GboardResult.Compiled else GboardResult.NotAGboardTheme
        val meta = files[PACK_META]?.decodeToString()?.let(::parsePackMeta).orEmpty()
        return GboardResult.Converted(
            themes = themes,
            packName = meta["name"].orEmpty(),
            packAuthor = meta["author"].orEmpty(),
            skipped = skipped,
        )
    }

    /** `key=value` lines, as `pack.meta` has them. */
    internal fun parsePackMeta(text: String): Map<String, String> =
        text.lineSequence()
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq).trim().lowercase() to line.substring(eq + 1).trim()
            }
            .toMap()

    // ---- one theme ----

    private class Metadata(
        val name: String?,
        val light: Boolean?,
        val preferBorder: Boolean,
        val sheets: List<String>,
        val borderSheets: List<String>,
        val landscapeSheets: List<String>,
    )

    private fun convert(files: Map<String, ByteArray>, fallbackName: String, packEntry: Boolean): GboardConverted? {
        val meta = files[METADATA]?.let { metadata(it) } ?: guessedMetadata(files) ?: return null
        val dropped = linkedSetOf<GboardUnsupported>()
        val readable = { names: List<String> ->
            names.mapNotNull { sheetName ->
                val bytes = FlexTheme.lookUp(files, sheetName)
                if (bytes == null || sheetName.endsWith(".binarypb", ignoreCase = true)) {
                    dropped += GboardUnsupported.UNREADABLE_STYLESHEET
                    null
                } else {
                    bytes.decodeToString()
                }
            }
        }
        val main = readable(meta.sheets)
        if (main.isEmpty()) return null
        val border = readable(meta.borderSheets)
        val landscape = readable(meta.landscapeSheets)

        val name = when {
            packEntry -> fallbackName.replace('_', ' ').trim()
            else -> meta.name?.takeIf { it.isNotBlank() } ?: fallbackName
        }.ifBlank { DEFAULT_NAME }

        val flat = GboardMapper(files, main, landscape, meta.light).convert(name, dropped)
        val bordered = if (border.isNotEmpty()) {
            GboardMapper(files, main + border, landscape, meta.light).convert(name, dropped)
        } else {
            null
        }
        val flatLook = flat?.let { GboardLook(it.first, bordered = false) }
        val borderLook = bordered?.let { GboardLook(it.first, bordered = true) }
        // A border flavour that changes nothing this keyboard draws would be a
        // second dot that looks exactly like the first.
        val same = flatLook != null && borderLook != null &&
            flatLook.converted.theme == borderLook.converted.theme &&
            flatLook.converted.images.keys == borderLook.converted.images.keys
        val ordered = if (meta.preferBorder) listOf(borderLook, flatLook) else listOf(flatLook, borderLook)
        val looks = ordered.filterNotNull().let { if (same) it.take(1) else it }
        // The count describes the look that comes first, which is the one the
        // theme's author meant to be seen.
        val first = (if (meta.preferBorder) bordered ?: flat else flat ?: bordered) ?: return null
        return GboardConverted(
            name = name,
            looks = looks,
            dropped = dropped.toList(),
            ruleCount = first.second.first,
            mappedRuleCount = first.second.second,
        )
    }

    /**
     * The metadata, or null when it is not a JSON object at all.
     *
     * Read leniently: forty themes in the Rboard repository carry a stray `]}`
     * after the object, so only the first balanced object is parsed.
     */
    private fun metadata(bytes: ByteArray): Metadata? {
        if (bytes.size > MAX_TEXT_BYTES) return null
        val text = firstJsonObject(bytes.decodeToString()) ?: return null
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        fun list(element: Any?): List<String> =
            (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .orEmpty()
        val flavors = (root["flavors"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        fun flavour(type: String) = flavors
            .filter { (it["type"] as? JsonPrimitive)?.content.equals(type, ignoreCase = true) }
            .flatMap { list(it["style_sheets"]) }
        return Metadata(
            name = (root["name"] as? JsonPrimitive)?.content,
            light = (root["is_light_theme"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
            preferBorder = (root["prefer_key_border"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
            sheets = list(root["style_sheets"]),
            borderSheets = flavour("BORDER"),
            landscapeSheets = flavour("LANDSCAPE"),
        )
    }

    /**
     * Metadata worked out from the file names, for a theme whose
     * `metadata.json` is missing or unparseable: every root stylesheet, with
     * the ones named for borders or landscape set aside as those flavours.
     */
    private fun guessedMetadata(files: Map<String, ByteArray>): Metadata? {
        val css = files.keys.filter { it.isRootCss() }.sorted()
        if (css.isEmpty()) return null
        val border = css.filter { it.contains("border", ignoreCase = true) }
        val landscape = css.filter { it.contains("landscape", ignoreCase = true) }
        return Metadata(
            name = null,
            light = null,
            preferBorder = false,
            sheets = css - border.toSet() - landscape.toSet(),
            borderSheets = border,
            landscapeSheets = landscape,
        )
    }

    /** The first `{ … }` in [text], balanced and string-aware. */
    internal fun firstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    // ---- the archive ----

    /**
     * The archive as bytes, keyed by entry name.
     *
     * **Entry names are never used as paths**, as in [FlexTheme]: a name is a
     * key in this map and nothing else, so `../../x` cannot reach the file
     * system because nothing here touches it. Names that try are dropped
     * anyway, so they cannot shadow a real entry through [FlexTheme.lookUp].
     */
    private fun unpack(input: InputStream, maxEntries: Int, maxTotal: Long, maxEntry: Int): Map<String, ByteArray> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            var count = 0
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++count > maxEntries) break
                val remaining = maxTotal - total
                if (remaining <= 0L) break
                val name = entry.name
                if (!isSafeName(name)) continue
                val cap = if (name.endsWith(".css") || name.endsWith(".json")) MAX_TEXT_BYTES else maxEntry
                val bytes = readCapped(zip, minOf(remaining, cap.toLong()).toInt())
                total += bytes.size
                files[name] = bytes
            }
        }
        return files
    }

    private fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && '\u0000' !in name && '\\' !in name && !name.startsWith("/") &&
            name.split('/').none { it == ".." }

    private fun readCapped(zip: ZipInputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var filled = 0
        while (filled < max) {
            val n = zip.read(buffer, 0, minOf(buffer.size, max - filled))
            if (n <= 0) break
            out.write(buffer, 0, n)
            filled += n
        }
        return out.toByteArray()
    }

    /** PNG, JPEG, GIF or WebP, by their first bytes. Anything else is not drawn. */
    internal fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        val png = at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47
        val jpeg = at(0) == 0xFF && at(1) == 0xD8
        val gif = at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code
        val webp = at(0) == 'R'.code && at(1) == 'I'.code && at(8) == 'W'.code && at(9) == 'E'.code
        return png || jpeg || gif || webp
    }

    /** An image the theme names, when the archive has it and it is one. */
    internal fun imageBytes(
        files: Map<String, ByteArray>,
        name: String?,
        maxBytes: Int = MAX_IMAGE_BYTES,
    ): ByteArray? {
        val path = name?.takeIf { it.isNotBlank() } ?: return null
        return lookUpImage(files, path)?.takeIf { it.size <= maxBytes && looksLikeImage(it) }
    }

    /**
     * An entry by the name a sheet gave it, then by that name with any
     * extension.
     *
     * `image_ref: "icon_emoticon"` is a real declaration in shipped themes —
     * Gboard names its own drawables without one, and the file in the archive
     * is `icon_emoticon.png`. Matched on the stem, and only when exactly one
     * entry has it, so a theme carrying both `logo.png` and `logo.jpg` is
     * ambiguous and resolves to neither rather than to whichever hashed first.
     */
    private fun lookUpImage(files: Map<String, ByteArray>, path: String): ByteArray? {
        FlexTheme.lookUp(files, path)?.let { return it }
        if ('.' in path.substringAfterLast('/')) return null
        val stem = path.substringAfterLast('/').substringAfterLast('\\')
        if (stem.isEmpty()) return null
        return files.entries
            .filter { it.key.substringAfterLast('/').substringBeforeLast('.') == stem }
            .singleOrNull()
            ?.value
    }

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private const val DEFAULT_NAME = "Gboard theme"
}

/** What reading a Gboard theme or an Rboard pack produced. */
sealed interface GboardResult {

    data class Converted(
        /** One for a theme file; one per readable theme for a pack. */
        val themes: List<GboardConverted>,
        /** From the pack's `pack.meta`; blank for a single theme. */
        val packName: String,
        val packAuthor: String,
        /** Themes in a pack that could not be read, compiled ones included. */
        val skipped: Int,
    ) : GboardResult {
        val isPack: Boolean get() = themes.size > 1 || packName.isNotEmpty() || skipped > 0
    }

    /**
     * A theme Gboard compiled to `.binarypb`. There is no published format to
     * read it by, so it is refused with its own message rather than as junk.
     */
    data object Compiled : GboardResult

    /** A ZIP, but not a Gboard theme or an Rboard pack. */
    data object NotAGboardTheme : GboardResult

    /** Truncated, not a ZIP at all, or past the size caps. */
    data object Unreadable : GboardResult
}

/** One Gboard theme, as one or two looks. */
data class GboardConverted(
    val name: String,
    /** The theme's preferred look first. Never empty. */
    val looks: List<GboardLook>,
    /** What it asked for that this keyboard cannot draw. */
    val dropped: List<GboardUnsupported>,
    /** Style rules read, and how many set something that landed. */
    val ruleCount: Int,
    val mappedRuleCount: Int,
    /** The preview picture a pack carries for it, when it has one. */
    val preview: ByteArray? = null,
) {
    // A ByteArray compares by identity, and this rides in Compose state.
    override fun equals(other: Any?): Boolean =
        this === other || (other is GboardConverted && name == other.name && looks == other.looks &&
            dropped == other.dropped && ruleCount == other.ruleCount && mappedRuleCount == other.mappedRuleCount)

    override fun hashCode(): Int = ((name.hashCode() * 31 + looks.hashCode()) * 31 + dropped.hashCode()) * 31 + ruleCount
}

/** A theme with or without Gboard's key borders. */
data class GboardLook(val converted: ConvertedTheme, val bordered: Boolean)

/** Something a Gboard theme asked for that has nowhere to go here. */
enum class GboardUnsupported {
    /** A listed stylesheet is compiled (`.binarypb`) or missing from the archive. */
    UNREADABLE_STYLESHEET,

    /**
     * A glyph the theme replaces that has no slot here.
     *
     * The ones that do land are carried as [ThemeSpec.keyIcons] — see
     * [GboardIcons]. What is left is the space bar's branding and Gboard's own
     * logo key, neither of which this keyboard draws at all.
     */
    KEY_ICONS,

    /** Padding inside each key, which Gboard sets per key and this app sets once. */
    KEY_SPACING,

    /** A font asked for by name. Gboard themes never carry the file. */
    FONT,

    /** No longer reported: the key shadows' colour comes across with the lift. Kept for old saved results. */
    SHADOW_COLOR,

    /** Corners rounded one by one — Gboard's rounded board top, usually. */
    PER_CORNER_RADIUS,

    /** Pictures on parts of the keyboard other than the board and the keys. */
    EXTRA_IMAGES,

    /** A scraped text colour was unreadable on its background and was replaced. */
    LOW_CONTRAST_FALLBACK,
}

/**
 * One look of a Gboard theme as a [ThemeSpec].
 *
 * The same rule the `.flex` and HeliBoard mappers work by: a field is written
 * only when the sheets say something unambiguous about that surface, and left
 * null for this app to derive otherwise.
 *
 * Gboard's own base stylesheet draws most of a theme from a dozen standard
 * variables (`color_state_key`, `color_label`, `color_header`, …), and many
 * themes — Gboard's stock ones among them — define only those variables and no
 * rules at all. [FALLBACK_SHEET] restates the rules that read them, so such a
 * theme converts; it sits under the theme's own sheets and loses every tie.
 */
internal class GboardMapper(
    private val files: Map<String, ByteArray>,
    sheets: List<String>,
    private val landscapeSheets: List<String>,
    private val light: Boolean?,
) {
    private val base = GboardCss.parse(FALLBACK_SHEET, orderStart = 0, base = true)
    private val parsed: List<GboardSheet> = run {
        var order = base.rules.size
        sheets.map { text -> GboardCss.parse(text, order).also { order += it.rules.size } }
    }
    private val style = GboardStyle(listOf(base) + parsed)

    /** The theme, and (rules read, rules that landed). */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun convert(name: String, dropped: MutableSet<GboardUnsupported>): Pair<ConvertedTheme, Pair<Int, Int>>? {
        val body = style.color(BODY, BG)
        val baseArea = style.color(BASE_AREA, BG)
        val keyFill = style.color(KEY, BG)
        if (body == null && baseArea == null && keyFill == null) return null

        val images = HashMap<String, ByteArray>()
        // The board photo belongs to the background element; two themes in the
        // Rboard repository put it on the key area instead, which draws the same.
        val backgroundRef = gboardString(style.value(BACKGROUND, IMAGE) ?: style.value(BODY, IMAGE))
        GboardTheme.imageBytes(files, backgroundRef)?.let { images[FlexTheme.IMAGE_BACKGROUND] = it }
        if (landscapeSheets.isNotEmpty()) {
            val landscapeStyle = GboardStyle(
                style.sheets + landscapeSheets.map { GboardCss.parse(it, orderStart = Int.MAX_VALUE / 2) },
            )
            val landscapeRef = gboardString(landscapeStyle.value(BACKGROUND, IMAGE))
            if (landscapeRef != backgroundRef) {
                GboardTheme.imageBytes(files, landscapeRef)?.let { images[GboardTheme.IMAGE_BACKGROUND_LANDSCAPE] = it }
            }
        }
        val hasImage = FlexTheme.IMAGE_BACKGROUND in images

        // Gboard paints the base area, then the photo, then the key area over
        // both. Without a photo the two areas are one colour to the eye; with
        // one, only the key area's colour sits over it, as a scrim.
        val board: Long = when {
            hasImage -> body ?: TRANSPARENT
            body != null && baseArea != null -> composite(body, baseArea)
            else -> body?.let { composite(it, OPAQUE_BLACK) } ?: baseArea ?: composite(keyFill!!, OPAQUE_BLACK)
        }
        val boardSeen = composite(board, if (hasImage) GENERIC_PHOTO else OPAQUE_BLACK)
        val dark = light?.not() ?: (Color(boardSeen).luminance() < DARK_THRESHOLD)

        val key = keyFill ?: TRANSPARENT
        val keySeen = composite(key, boardSeen)
        val function = fill(FUNCTION_KEY)
        // Gboard's enter key is often no key face at all but a round badge
        // behind its icon, which the sheets colour as a separate element.
        // When the theme says outright that both are see-through, the key is
        // drawn that way, as Gboard does: the 3D packs' borderless looks
        // leave every face clear, enter included, and filling enter with the
        // accent made it the one orange slab on a black board.
        val enterFace = fill(ENTER)
        val enterBadge = style.color(ENTER_BADGE, FG)
        val enter = enterFace?.takeIf { it.isVisible() }
            ?: enterBadge?.takeIf { it.isVisible() }
            ?: TRANSPARENT.takeIf { enterFace != null && enterBadge != null }
        val accent = accentColor(enter, boardSeen)
        val enterFill = enter ?: accent
        val enterSeen = composite(enterFill, boardSeen)
        val space = fill(SPACE)
        val strip = (style.color(HEADER, BG) ?: style.color(CANDIDATES, BG))
            ?.takeIf { it != board }
        val popup = style.color(POPUP, BG)
        val popupSeen = popup?.let { composite(it, boardSeen) }

        texture(KEY, ASSET_KEY_TEXTURE, images)
        texture(FUNCTION_KEY, ASSET_KEY_TEXTURE_MODIFIER, images)
        texture(ENTER, ASSET_KEY_TEXTURE_ENTER, images)
        texture(SPACE, ASSET_KEY_TEXTURE_SPACE, images)
        // The theme's own key glyphs. They ride in `images` under the asset
        // prefix, so the storing side needs nothing new: `withExtractedImages`
        // already writes every asset slot out and this one names a `keyIcons`
        // entry when it lands.
        val glyphs = keyIcons(images)

        val keyText = textOn(style.color(LABEL, FG), keySeen, dropped)
        val spaceText = readableOn(style.color(SPACE_LABEL, FG), composite(space ?: key, boardSeen))
            ?.takeIf { it != keyText }
        val (shape, radius) = keyShape()
        val edgeWidth = style.number(KEY, EDGE_WIDTH)?.takeIf { it > 0f }
        val edgeColor = style.color(KEY, EDGE_COLOR)?.takeIf { it.isVisible() }
        val elevation = style.number(KEY, ELEVATION)?.takeIf { it > 0f }?.coerceAtMost(MAX_ELEVATION_DP)
        val shadowColor = style.color(KEY, SHADOW_COLOR)?.takeIf { it.isVisible() }
        // Gboard draws a key's lift as a hard band of the shadow colour under
        // the face, not as a soft blur. A theme that colours it (the 3D packs'
        // orange slab) is drawn as such, by a skin; see [GboardKeySkin].
        val raised = if (elevation != null && shadowColor != null && shape != KeyShapeKind.NONE &&
            (key ushr 24) == 0xFFL
        ) {
            GboardKeySkin.raised(
                depth = elevation,
                radius = when (shape) {
                    KeyShapeKind.SHARP -> 0f
                    KeyShapeKind.PILL -> Float.MAX_VALUE
                    else -> radius?.toFloat() ?: GboardKeySkin.DEFAULT_RADIUS
                },
                edge = if (edgeWidth != null && edgeColor != null) edgeWidth.coerceAtMost(MAX_EDGE_DP) else 0f,
            )
        } else {
            null
        }

        val theme = ThemeSpec(
            id = "",
            name = name,
            dark = dark,
            boardBackground = board,
            suggestionBarBackground = strip,
            navigationBarBackground = style.color(NAVBAR, BG)?.takeIf { it.isVisible() },
            keyBackground = key,
            keyText = keyText,
            pressedKeyBackground = style.color(KEY, BG, PRESSED),
            modifierKeyBackground = function ?: key,
            modifierKeyText = (style.color(FUNCTION_LABEL, FG) ?: style.color(FUNCTION_ICON, FG))
                ?.let { textOn(it, composite(function ?: key, boardSeen), dropped) },
            enterKeyBackground = enterFill,
            enterKeyText = readableOn(style.color(ENTER_ICON, FG), enterSeen) ?: onColorFor(enterSeen),
            hintText = style.color(HINT, FG)?.takeIf { it.isVisible() },
            keyBorderColor = if (edgeWidth != null) edgeColor else null,
            keyBorderWidthDp = if (edgeColor != null) edgeWidth?.coerceAtMost(MAX_EDGE_DP) ?: 0f else 0f,
            keyElevationDp = elevation ?: 0f,
            keyShadowColor = shadowColor,
            keySkin = raised,
            keyShape = shape,
            keyCornerRadiusDp = radius,
            accent = accent,
            gestureTrailColor = style.color(TRACK, FG)?.takeIf { it.isVisible() },
            popupBackground = popup,
            popupText = style.color(POPUP_LABEL, FG)?.let { textOn(it, popupSeen ?: keySeen, dropped) },
            popupSelectedBackground = style.color(POPUP_ITEM, BG, PRESSED)?.takeIf { it.isVisible() },
            popupSelectedText = style.color(POPUP_LABEL, FG, PRESSED)?.takeIf { it.isVisible() },
            suggestionText = style.color(CANDIDATE, FG)?.takeIf { it.isVisible() },
            dividerColor = style.color(DIVIDER, FG)?.takeIf { it.isVisible() },
            toolbarIcon = style.color(TOOL_ICON, FG)?.takeIf { it.isVisible() },
            // Gboard gives a suggestion its own key face, and this keyboard
            // draws suggestions on chips, so the two are the same surface.
            chipBackground = style.color(CANDIDATE_KEY, BG)?.takeIf { it != strip && it != board },
            keyOverrides = buildMap {
                val spaceFill = space?.takeIf { it != key }
                if (spaceFill != null || spaceText != null) {
                    put(KEY_SPACE, KeyOverride(background = spaceFill, text = spaceText))
                }
            },
        )
        noteLosses(dropped, glyphs)
        val rules = style.themeRules
        return ConvertedTheme(theme, images) to (rules.size to rules.count { style.lands(it) })
    }

    /**
     * The theme's own key glyphs, written into [images] under
     * [ASSET_KEY_ICON_PREFIX]; the class names that landed come back, so
     * [noteLosses] can report only the ones that did not.
     *
     * A slot is filled once: [GboardIcons.SLOTS] lists two spellings for
     * several keys and the first one the theme states is the one it meant.
     */
    private fun keyIcons(images: MutableMap<String, ByteArray>): Set<String> {
        // The glyph classes the theme actually writes, under the table's own
        // spelling. Walked rather than queried because a theme may write
        // `.icon_key_main_category_smiley_dark_theme`, and a cascade query can
        // only ask about classes an element literally carries.
        val present = LinkedHashMap<String, MutableList<String>>()
        for (rule in style.themeRules) {
            if (ICON_IMAGE !in rule.properties) continue
            val selector = rule.selector ?: continue
            if (selector.state != null) continue
            val className = selector.classes.singleOrNull() ?: continue
            val normalized = GboardIcons.normalize(className)
            if (normalized !in GboardIcons.CLASSES) continue
            present.getOrPut(normalized) { ArrayList() }.let { if (className !in it) it += className }
        }
        val landed = linkedSetOf<String>()
        for ((className, slot) in GboardIcons.SLOTS) {
            val key = ASSET_KEY_ICON_PREFIX + slot
            if (key in images) continue
            for (written in present[className].orEmpty()) {
                // Through the cascade rather than off the rule, so a theme that
                // states the same glyph twice gets the one Gboard would draw —
                // and so the rule counts as read in the "N of M" the import
                // shows (see [GboardStyle.lands]).
                val ref = gboardString(style.value(setOf(written), ICON_IMAGE)) ?: continue
                val bytes = GboardTheme.imageBytes(files, ref, GboardIcons.MAX_ICON_BYTES) ?: continue
                images[key] = bytes
                landed += written
                break
            }
        }
        return landed
    }

    /**
     * The theme's one accent: the glide trail, else the enter key, else the
     * accent variables Gboard's own themes define. Composited onto the board,
     * because a see-through accent paints an invisible trail.
     */
    private fun accentColor(enter: Long?, board: Long): Long {
        val pick = listOfNotNull(
            style.color(TRACK, FG),
            enter,
            gboardColor(style.variable("default_generic_accent_color")),
            gboardColor(style.variable("color_state_action")),
        ).firstOrNull { it.isVisible() } ?: return ThemeSpec(id = "", name = "").accent
        return composite(pick, board)
    }

    /**
     * Gboard's `background_shape` and `background_corner_radius` as a key
     * shape. An empty shape is Gboard's way of drawing no key face at all.
     */
    private fun keyShape(): Pair<KeyShapeKind, Int?> {
        val shape = gboardString(style.value(KEY, SHAPE))?.lowercase()
        val radius = style.number(KEY, RADIUS)?.takeIf { it >= 0f }
        return when {
            shape == "" -> KeyShapeKind.NONE to null
            shape == "square" -> KeyShapeKind.SHARP to 0
            radius == null -> KeyShapeKind.ROUNDED to null
            radius <= 0f -> KeyShapeKind.SHARP to 0
            // Gboard's key face is about 36 dp tall inside its padding, so a
            // radius past half of that is a pill at any real key height.
            radius >= PILL_RADIUS_DP -> KeyShapeKind.PILL to null
            else -> KeyShapeKind.ROUNDED to radius.roundedDp()
        }
    }

    /**
     * A key class's fill, or null when that class draws no face: an empty
     * `background_shape` is how a Gboard sheet hides the space bar or the enter
     * key's face in its borderless look, whatever colour it also names.
     */
    private fun fill(element: Set<String>): Long? {
        if (gboardString(style.value(element, SHAPE)) == "") return TRANSPARENT
        return style.color(element, BG)
    }

    /** A key class's own picture, when the sheet gives it one the archive has. */
    private fun texture(element: Set<String>, slot: String, images: MutableMap<String, ByteArray>) {
        val ref = gboardString(style.value(element, IMAGE)) ?: return
        GboardTheme.imageBytes(files, ref)?.let { images[slot] = it }
    }

    /**
     * A scraped text colour, unless it is unreadable where it lands, in which
     * case a derived one is used and the swap reported.
     */
    private fun textOn(color: Long?, background: Long, dropped: MutableSet<GboardUnsupported>): Long {
        readableOn(color, background)?.let { return it }
        if (color != null && color.isVisible()) dropped += GboardUnsupported.LOW_CONTRAST_FALLBACK
        return onColorFor(background)
    }

    /**
     * [color] when it reads on [background], else null.
     *
     * The enter glyph and the space bar's label go through this rather than
     * [textOn], silently. Gboard draws both as pictures in most community
     * themes (`icon_enter.png`) or hides the space bar's branding on purpose,
     * so the colour a sheet leaves on them is often the key's own colour and
     * was never meant to be read. Deriving a legible one there is not a loss
     * worth a line in the dialog — every third theme would carry it.
     */
    private fun readableOn(color: Long?, background: Long): Long? {
        if (color == null || !color.isVisible()) return null
        val seen = composite(color, background)
        return color.takeIf { contrastRatio(seen, background) >= Readability.POOR_CONTRAST }
    }

    /**
     * The named losses, read off every rule of the theme's own sheets.
     *
     * [glyphs] names the key-glyph classes that *did* come across, so a theme
     * whose icons all landed is not told its icons were dropped — which is what
     * it used to be told, because carrying them was impossible until the icon
     * system learned to draw a raster (issue #504).
     */
    private fun noteLosses(dropped: MutableSet<GboardUnsupported>, glyphs: Set<String>) {
        for (rule in style.themeRules) {
            val props = rule.properties
            val classes = rule.selector?.classes.orEmpty()
            if (FONT_FAMILY in props) dropped += GboardUnsupported.FONT
            if (props.keys.any { it != RADIUS && CORNER in it }) dropped += GboardUnsupported.PER_CORNER_RADIUS
            if (KEYTOP in classes) {
                if (props.keys.any { it.startsWith(PADDING) }) dropped += GboardUnsupported.KEY_SPACING
            }
            if (classes.none { it in glyphs } &&
                GboardTheme.imageBytes(files, gboardString(style.resolve(props[ICON_IMAGE]))) != null
            ) {
                dropped += GboardUnsupported.KEY_ICONS
            }
            val picture = GboardTheme.imageBytes(files, gboardString(style.resolve(props[IMAGE])))
            if (picture != null && classes.isNotEmpty() && classes !in IMAGE_ELEMENTS) {
                dropped += GboardUnsupported.EXTRA_IMAGES
            }
        }
    }

    private companion object {
        const val BG = "background_color"
        const val FG = "color"
        const val IMAGE = "background_image_ref"
        const val ICON_IMAGE = "image_ref"
        const val EDGE_WIDTH = "edge_width"
        const val EDGE_COLOR = "edge_color"
        const val ELEVATION = "elevation"
        const val SHAPE = "background_shape"
        const val RADIUS = "background_corner_radius"
        const val CORNER = "corner_radius"
        const val SHADOW_COLOR = "shadow_color"
        const val FONT_FAMILY = "font_family"
        const val PADDING = "padding"
        const val PRESSED = "pressed"
        const val KEYTOP = "keytop"
        const val KEY_SPACE = "SPACE"

        val BODY = setOf("keyboard-body-area")
        val BASE_AREA = setOf("keyboard-base-area")
        val BACKGROUND = setOf("keyboard-background")
        val HEADER = setOf("keyboard-header-area")
        val CANDIDATES = setOf("candidates-area")
        val KEY = setOf(KEYTOP)
        val FUNCTION_KEY = setOf(KEYTOP, "dark")
        val SPACE = setOf(KEYTOP, "for-space-bar", "space_bar")
        // The plain enter key carries `for-action-default-key` too, and the
        // bordered flavours style it there: the 3D packs paint
        // `.keytop.for-action-key` orange for Search and Send but leave the
        // default enter `.keytop.for-action-key.for-action-default-key` black.
        // Asked without the class, every such theme got the orange one.
        val ENTER = setOf(KEYTOP, "for-action-key", "for-action-default-key")
        val LABEL = setOf("label")
        val FUNCTION_LABEL = setOf("label", "for-function-key")
        val FUNCTION_ICON = setOf("icon", "for-function-key")
        // Every class the element carries, not just the ones that name it.
        // A query only matches a rule whose classes it *contains*, so
        // `.icon.for-action-key.for-action-default-key` — which 736 of the
        // rules in the Rboard corpus are written as, more than are written the
        // short way — matched nothing at all until `for-action-default-key` was
        // listed here, and every such theme lost its enter glyph's colour.
        val ENTER_ICON = setOf("icon", "for-action-key", "for-action-default-key")
        val ENTER_BADGE = setOf("background-icon", "for-action-key")
        val SPACE_LABEL = setOf("label", "for-space-key")
        val HINT = setOf("label", "secondary")
        val CANDIDATE = setOf("label", "for-candidate-key")

        /** The face behind a suggestion, which this keyboard draws as a chip. */
        val CANDIDATE_KEY = setOf(KEYTOP, "for-candidate-key")
        val POPUP = setOf("popup")
        val POPUP_ITEM = setOf("popup-item")
        val POPUP_LABEL = setOf("label", "for-popup-item")
        val TRACK = setOf("track", "for-gesture")
        val NAVBAR = setOf("navbar")
        val DIVIDER = setOf("divider", "vertical", "for-candidate-key")
        // `v2` for the same reason `for-action-default-key` is on [ENTER_ICON]:
        // the newer themes write `.icon.for-access-point-icon.v2`.
        val TOOL_ICON = setOf("icon", "for-access-point-icon", "v2")

        /** The elements whose pictures do land: the board and the four key classes. */
        val IMAGE_ELEMENTS = setOf(
            BACKGROUND, BODY, KEY, FUNCTION_KEY, ENTER, setOf(KEYTOP, "for-action-key"),
            setOf(KEYTOP, "for-space-bar"), setOf("space_bar"),
        )

        const val TRANSPARENT = 0x00000000L
        const val OPAQUE_BLACK = 0xFF000000L

        /** What a photo averages to, for judging text against a board we cannot see yet. */
        const val GENERIC_PHOTO = 0xFF6E6E6EL
        const val DARK_THRESHOLD = 0.5f
        const val PILL_RADIUS_DP = 18f
        const val MAX_EDGE_DP = 4f
        const val MAX_ELEVATION_DP = 8f

        /**
         * The rules Gboard's own base stylesheet reads its standard variables
         * with. Restated from what the themes define, not copied from Gboard:
         * a variable name that half the themes in the Rboard repository define
         * and none of them use in a rule of their own is one Gboard reads.
         */
        val FALLBACK_SHEET = """
            @def color_label_function @color_label;
            @def color_label_function_key @color_label_function;
            @def default_popup_label_color @color_popup_label;
            @def default_popup_item_color_pressed @color_state_popup_item_pressed;
            .keyboard-body-area { background_color: @color_base; }
            .keyboard-header-area { background_color: @color_header; }
            .keyboard-background { background_image_ref: @image_keyboard_background; }
            .keytop { background_color: @color_state_key; }
            .keytop:pressed { background_color: @color_state_key_pressed; }
            .keytop.dark { background_color: @color_state_key_dark; }
            .keytop.dark:pressed { background_color: @color_state_key_dark_pressed; }
            .keytop.for-space-bar { background_color: @color_state_space_bar; }
            .keytop.for-space-bar:pressed { background_color: @color_state_space_bar_pressed; }
            .keytop.for-action-key { background_color: @default_generic_accent_color; }
            .label, .icon { color: @color_label; }
            .label.for-function-key, .icon.for-function-key { color: @color_label_function_key; }
            .icon.for-action-key { color: @color_icon_action; }
            .label.for-space-key { color: @color_label_space_key; }
            .label.secondary { color: @color_label_secondary; }
            .label.for-candidate-key { color: @color_state_label_candidate; }
            .popup { background_color: @color_popup_background; }
            .popup-item:pressed { background_color: @default_popup_item_color_pressed; }
            .label.for-popup-item { color: @default_popup_label_color; }
            .navbar { background_color: @color_navbar; }
            .divider.vertical.for-candidate-key { color: @color_candidate_separator; }
        """.trimIndent()
    }
}

package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A single layout as a shareable file.
 *
 * A versioned envelope rather than the bare object the theme export writes, and
 * for a specific reason: every [com.wasimaster.wmkeyboard.core.theme.ThemeSpec]
 * field has a default, so decoding an arbitrary JSON object succeeds and yields
 * an all-defaults theme instead of an error — importing a shopping list would
 * "work". A layout has no sane all-defaults value, and the format tag is the one
 * thing that can say "this is not a layout file" before anything is applied.
 *
 * Past the tag it is deliberately tolerant. The raw-JSON editor means
 * hand-written files, so mistakes will be common, and refusing an import leaves
 * someone with a file they cannot use and no idea which of two hundred keys is
 * wrong. [LayoutSpec.repair] fixes what it can and reports every change.
 */
@Serializable
private data class LayoutEnvelope(
    val format: String,
    val version: Int,
    val appVersion: Int = 0,
    val appVersionName: String = "",
    val layout: LayoutSpec,
    /** The Your icons pictures the layout's keys wear; see [LayoutIcon]. */
    val icons: List<LayoutIcon> = emptyList(),
)

/**
 * A picture out of the user's icon library that travels inside a layout file,
 * because a key wearing one stores only `u:<id>` and the phone the file is
 * opened on has no such picture yet.
 *
 * [data] is the file's bytes in base64; this module only carries it. The app
 * decodes it, checks it the way any imported icon is checked, and adds it to
 * the library, where it lands under the same [id], because the id is a hash
 * of those bytes. An entry that fails any of that is dropped and the key draws
 * its text.
 */
@Serializable
data class LayoutIcon(val id: String, val name: String = "", val data: String)

/**
 * Several layouts in one file (#970): every layout in use, to move to another
 * phone or keep in one place. The layouts inside are the same [LayoutSpec]s
 * a one-layout file holds, ids and all, so the keys of one that open another
 * still find it on the other side.
 */
@Serializable
private data class LayoutBundle(
    val format: String,
    val version: Int,
    val appVersion: Int = 0,
    val appVersionName: String = "",
    val layouts: List<LayoutSpec>,
    val icons: List<LayoutIcon> = emptyList(),
)

/** A layout read off disk, with whatever had to be fixed to make it usable. */
data class ImportedLayout(
    val layout: LayoutSpec,
    /** One line per change the repair pass made, for the screen to resolve. */
    val repairNotes: List<LayoutMessage>,
    /** Version code of the build that wrote the file; 0 when unstated. */
    val fromAppVersion: Int,
    /** Pictures the file carried for its keys; see [LayoutIcon]. */
    val icons: List<LayoutIcon> = emptyList(),
)

object LayoutFile {

    const val FORMAT = "wmkeyboard-layout"
    const val VERSION = 1

    /** Matches the settings backup's `wmsettings.json` shape. */
    const val FILE_EXTENSION = "wmlayout.json"

    /**
     * Plain JSON rather than a vendor type. A custom MIME would stop most file
     * managers and chat apps from offering the file at all, and the format tag
     * inside already does the identifying.
     */
    const val MIME_TYPE = "application/json"

    /**
     * What the import picker accepts. Deliberately permissive: providers
     * routinely hand back `application/octet-stream` or `text/plain` for a
     * `.json` file, and the strict check is the format tag, not the MIME.
     */
    val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

    /**
     * The name an export suggests. With [stampMs], the time goes after the
     * layout's name (#970), so exporting the same layout twice gives two files
     * that sort by date instead of a "Mine (1)" the picker made up. [Locale.US]
     * for the same reason the backups use it: a Thai-Buddhist locale would
     * stamp 2569.
     */
    fun fileName(layout: LayoutSpec, stampMs: Long? = null): String {
        val stem = layout.name.ifBlank { "layout" }
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
            .trim()
            .ifBlank { "layout" }
        val stamp = stampMs?.let { "-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(it)) }.orEmpty()
        return "$stem$stamp.$FILE_EXTENSION"
    }

    /**
     * An export: every field of every key, indented two spaces (#970), so the
     * file reads and diffs as text. The import does not care either way.
     */
    fun encode(
        layout: LayoutSpec,
        appVersion: Int,
        appVersionName: String,
        icons: List<LayoutIcon> = emptyList(),
    ): String =
        exportJson.encodeToString(
            LayoutEnvelope(FORMAT, VERSION, appVersion, appVersionName, layout, icons),
        )

    /** What a key's icon names to wear a picture from the user's icon library. */
    private val LIBRARY_ICON = Regex(""""u:([0-9a-f]{16})"""")

    /**
     * The ids of every library picture [layouts] wear, for an export to carry.
     * Read off the encoded form rather than by walking the keys, so a key in a
     * flick, a layer or a popup is found the same as one on the board.
     */
    fun libraryIconIds(layouts: List<LayoutSpec>): Set<String> =
        layouts.flatMapTo(LinkedHashSet()) { layout ->
            LIBRARY_ICON.findAll(LayoutCodec.json.encodeToString(layout)).map { it.groupValues[1] }
        }

    private val exportJson = Json(LayoutCodec.json) {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /**
     * Encodes a layout the way the shipped assets under `assets/layouts` are
     * written: indented, and with defaulted fields left out.
     *
     * [encode] writes every field of every key, which is right for a file the
     * user exports — it is self-describing and survives a format change — and
     * wrong for one committed to the repository. Measured over the Keyman
     * corpus the difference is 24 MB against roughly 3 MB for the same 867
     * layouts, all of it `"output":null,"shiftLabel":null` repeated per key. The
     * APK barely notices either way, because both deflate to about the same
     * thing; the repository notices a great deal.
     *
     * No app version is recorded: an asset ships with the build it is in, so
     * the field would say nothing and cost a line in every file.
     */
    fun encodeAsset(layout: LayoutSpec): String =
        assetJson.encodeToString(LayoutEnvelope(FORMAT, VERSION, 0, "", layout))

    private val assetJson = Json(LayoutCodec.json) {
        encodeDefaults = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /**
     * Parses [text], or returns null when it is not a layout file at all — the
     * one strict check in the import path.
     */
    fun decode(text: String): ImportedLayout? {
        val envelope = runCatching {
            LayoutCodec.json.decodeFromString<LayoutEnvelope>(text.firstJsonDocument())
        }.getOrNull() ?: return null
        if (envelope.format != FORMAT) return null
        val repaired = envelope.layout.repair()
        return ImportedLayout(
            layout = repaired.spec,
            repairNotes = repaired.repairNotes,
            fromAppVersion = envelope.appVersion,
            icons = envelope.icons,
        )
    }

    /** Tags [encodeBundle]'s files, apart from a one-layout file's [FORMAT]. */
    const val BUNDLE_FORMAT = "wmkeyboard-layouts"

    /** Same extension family as a one-layout file, so the picker and the share sheet treat it alike. */
    const val BUNDLE_EXTENSION = "wmlayouts.json"

    /** "Layouts-20261027-1405.wmlayouts.json": dated like a one-layout export. */
    fun bundleFileName(stampMs: Long): String =
        "Layouts-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(stampMs)) + ".$BUNDLE_EXTENSION"

    /** [layouts] in one file, indented like [encode]. */
    fun encodeBundle(
        layouts: List<LayoutSpec>,
        appVersion: Int,
        appVersionName: String,
        icons: List<LayoutIcon> = emptyList(),
    ): String =
        exportJson.encodeToString(LayoutBundle(BUNDLE_FORMAT, VERSION, appVersion, appVersionName, layouts, icons))

    /**
     * Every layout in a file [encodeBundle] wrote, each repaired the way
     * [decode] repairs one, or null when [text] is not such a file. An empty
     * bundle is still null: there is nothing to ask the user about.
     */
    fun decodeBundle(text: String): List<ImportedLayout>? {
        val bundle = runCatching {
            LayoutCodec.json.decodeFromString<LayoutBundle>(text.firstJsonDocument())
        }.getOrNull() ?: return null
        if (bundle.format != BUNDLE_FORMAT || bundle.layouts.isEmpty()) return null
        return bundle.layouts.map { layout ->
            val repaired = layout.repair()
            ImportedLayout(repaired.spec, repaired.repairNotes, bundle.appVersion, bundle.icons)
        }
    }

    /**
     * The layout inside an exported file, unrepaired, or null when [text] is
     * not a layout file.
     *
     * For the raw-JSON editor, which runs its own repair pass and reports the
     * notes itself. It takes the bare layout it printed, but what people paste
     * into it is just as often the file the export wrote — the same layout
     * inside the envelope — and refusing that as "not valid layout JSON" read
     * as the file being broken (#71).
     */
    fun unwrap(text: String): LayoutSpec? {
        val envelope = runCatching {
            LayoutCodec.json.decodeFromString<LayoutEnvelope>(text.firstJsonDocument())
        }.getOrNull() ?: return null
        if (envelope.format != FORMAT) return null
        return envelope.layout
    }
}

package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
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
)

/** A layout read off disk, with whatever had to be fixed to make it usable. */
data class ImportedLayout(
    val layout: LayoutSpec,
    /** One line per change the repair pass made, for the screen to resolve. */
    val repairNotes: List<LayoutMessage>,
    /** Version code of the build that wrote the file; 0 when unstated. */
    val fromAppVersion: Int,
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

    fun fileName(layout: LayoutSpec): String {
        val stem = layout.name.ifBlank { "layout" }
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
            .trim()
            .ifBlank { "layout" }
        return "$stem.$FILE_EXTENSION"
    }

    fun encode(layout: LayoutSpec, appVersion: Int, appVersionName: String): String =
        LayoutCodec.json.encodeToString(
            LayoutEnvelope(FORMAT, VERSION, appVersion, appVersionName, layout),
        )

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
        )
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

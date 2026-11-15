package com.wasimaster.wmkeyboard.core.helpvisuals

/**
 * The look a help picture was captured in. Every picture exists once per look,
 * and each look has a download pack of its own, so nobody fetches the pictures
 * of a look they never use.
 */
enum class HelpTheme(val id: String) {
    LIGHT("light"),
    DARK("dark"),
    ;

    val other: HelpTheme get() = if (this == LIGHT) DARK else LIGHT

    companion object {
        fun of(id: String): HelpTheme? = entries.firstOrNull { it.id == id }
        fun of(dark: Boolean): HelpTheme = if (dark) DARK else LIGHT
    }
}

/** How the frames of a [HelpVisual] are laid out in the "?" dialog. */
enum class HelpVisualKind(val id: String) {
    /** One picture. */
    STILL("still"),

    /** One animated WebP, a few seconds of the feature in use. */
    CLIP("clip"),

    /**
     * The setting off and the setting on, cropped to what differs. Drawn side
     * by side when the crop is narrow and one above the other when it is wide.
     */
    COMPARE("compare"),

    /** One picture per option or per step, paged, each under its label. */
    GALLERY("gallery"),
    ;

    companion object {
        fun of(id: String): HelpVisualKind? = entries.firstOrNull { it.id == id }
    }
}

/** One file on the data repository, named by the SHA-256 of its bytes. */
class HelpFile(val sha256: String, val bytes: Long) {
    /** Where the file sits in the data repository. */
    val repoPath: String get() = "$FILES_DIR/${sha256.take(2)}/$sha256.webp"

    /** The file's name on disk and inside a pack. */
    val fileName: String get() = "$sha256.webp"

    private companion object {
        const val FILES_DIR = "help/files"
    }
}

/**
 * One picture of a [HelpVisual], in each look it was captured in. [label] is
 * the resource name of the words drawn with it ("Off", an option's name), or
 * null for a picture that needs none. [width] and [height] are the picture's
 * own pixels, the same in both looks, so the dialog can leave the right room
 * before the file arrives.
 */
class HelpFrame(
    val label: String?,
    val width: Int,
    val height: Int,
    private val light: HelpFile?,
    private val dark: HelpFile?,
) {
    fun file(theme: HelpTheme): HelpFile? = if (theme == HelpTheme.LIGHT) light else dark

    /** Width over height. */
    val aspect: Float get() = if (height > 0) width.toFloat() / height else 1f
}

/** The picture, clip or comparison shown above one setting's explanation. [key] is the title's resource name. */
class HelpVisual(val key: String, val kind: HelpVisualKind, val frames: List<HelpFrame>) {
    /** The bytes of every frame in [theme]. */
    fun bytes(theme: HelpTheme): Long = frames.sumOf { it.file(theme)?.bytes ?: 0L }
}

/** Every picture of one look in a single archive. */
class HelpPack(val theme: HelpTheme, val sha256: String, val bytes: Long) {
    /** The published name, which an import is recognised by. */
    val fileName: String get() = fileName(theme, sha256)

    val repoPath: String get() = "$PACKS_DIR/$fileName"

    companion object {
        private const val PACKS_DIR = "help/packs"
        private const val SHORT_SHA = 12
        private val NAME = Regex("""^wmkb-help-(light|dark)-[0-9a-f]{$SHORT_SHA}\.zip$""")

        fun fileName(theme: HelpTheme, sha256: String): String =
            "wmkb-help-${theme.id}-${sha256.take(SHORT_SHA)}.zip"

        /** The look a pack file named [name] holds, or null when it is not a pack's name. */
        fun themeOf(name: String): HelpTheme? =
            NAME.matchEntire(name)?.groupValues?.get(1)?.let(HelpTheme::of)
    }
}

/**
 * Which settings have a picture, and the files each one is made of. Shipped in
 * the APK (`assets/help/visuals.tsv`, written by tools/helpvisuals/build.py),
 * so the app knows what a "?" has to show, how big it is and what it must hash
 * to before anything is fetched.
 *
 * The format is one record per line, tab separated:
 *
 *     wmhelp  1
 *     pack    light  <sha256>  <bytes>
 *     visual  <title resource name>  <kind>
 *     frame   <label resource name or ->  <width>  <height>  <light sha or ->  <bytes>  <dark sha or ->  <bytes>
 *
 * A `frame` belongs to the `visual` above it. A line that does not parse is
 * skipped, and a visual left with no frames is dropped, so a newer index read
 * by an older app loses a picture rather than the whole list.
 */
class HelpVisualIndex(
    val visuals: Map<String, HelpVisual>,
    private val packs: Map<HelpTheme, HelpPack>,
) {
    fun pack(theme: HelpTheme): HelpPack? = packs[theme]

    val isEmpty: Boolean get() = visuals.isEmpty()

    companion object {
        val EMPTY = HelpVisualIndex(emptyMap(), emptyMap())

        private const val MAGIC = "wmhelp"
        private const val VERSION = 1
        private const val NONE = "-"
        private val SHA = Regex("^[0-9a-f]{64}$")

        fun parse(text: String): HelpVisualIndex {
            val lines = text.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.startsWith("#") }
            val packs = HashMap<HelpTheme, HelpPack>()
            val visuals = LinkedHashMap<String, HelpVisual>()
            var key: String? = null
            var kind: HelpVisualKind? = null
            var frames = ArrayList<HelpFrame>()
            var versionOk = false
            fun close() {
                val k = key
                val what = kind
                if (k != null && what != null && frames.isNotEmpty()) visuals[k] = HelpVisual(k, what, frames)
                key = null
                kind = null
                frames = ArrayList()
            }
            for (line in lines) {
                val cells = line.split('\t')
                when (cells[0]) {
                    MAGIC -> versionOk = cells.getOrNull(1)?.toIntOrNull() == VERSION
                    "pack" -> pack(cells)?.let { packs[it.theme] = it }
                    "visual" -> {
                        close()
                        key = cells.getOrNull(1)?.takeIf { it.isNotEmpty() }
                        kind = cells.getOrNull(2)?.let(HelpVisualKind::of)
                    }
                    "frame" -> frame(cells)?.let { frames += it }
                }
            }
            close()
            return if (versionOk) HelpVisualIndex(visuals, packs) else EMPTY
        }

        private fun pack(cells: List<String>): HelpPack? {
            val theme = cells.getOrNull(1)?.let(HelpTheme::of) ?: return null
            val sha = cells.getOrNull(2)?.takeIf { SHA.matches(it) } ?: return null
            val bytes = cells.getOrNull(3)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return HelpPack(theme, sha, bytes)
        }

        private fun frame(cells: List<String>): HelpFrame? {
            if (cells.size < 8) return null
            val width = cells[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val height = cells[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val light = file(cells[4], cells[5])
            val dark = file(cells[6], cells[7])
            if (light == null && dark == null) return null
            return HelpFrame(cells[1].takeUnless { it == NONE || it.isEmpty() }, width, height, light, dark)
        }

        private fun file(sha: String, bytes: String): HelpFile? {
            if (!SHA.matches(sha)) return null
            val length = bytes.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return HelpFile(sha, length)
        }
    }
}

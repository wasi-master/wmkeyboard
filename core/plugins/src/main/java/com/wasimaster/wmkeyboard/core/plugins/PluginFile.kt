package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The outcome of reading a `.wmplugin` file. */
sealed interface PluginImportResult {
    data class Imported(val plugin: InstalledPlugin, val replaced: Boolean) : PluginImportResult

    /** No manifest, or a manifest for something else entirely. */
    data object NotAPlugin : PluginImportResult

    /** A plugin this build will not accept. [reasonText] is user-facing. */
    data class Rejected(val reasonText: PluginText) : PluginImportResult

    /** [PluginStore.MAX_PLUGINS] reached. */
    data object TooManyPlugins : PluginImportResult

    /** Unreadable archive, or nowhere to write. */
    data object Failed : PluginImportResult
}

/** The outcome of reading a `.wmplugin` file without installing it. */
sealed interface PluginReadResult {
    /** A plugin [PluginFile.import] would accept, with its script, other modules and assets. */
    data class Ok(
        val manifest: PluginManifest,
        val permissions: List<PluginPermission>,
        val script: String,
        /** API 5: every other `.py` file, by its name in the archive. */
        val modules: Map<String, String> = emptyMap(),
        /** API 5: `assets/<name>` entries, by name. */
        val assets: Map<String, ByteArray> = emptyMap(),
    ) : PluginReadResult

    /** No manifest, or a manifest for something else entirely. */
    data object NotAPlugin : PluginReadResult

    /** A plugin this build will not accept. [reasonText] is user-facing. */
    data class Rejected(val reasonText: PluginText) : PluginReadResult

    /** Not a readable archive. */
    data object Failed : PluginReadResult
}

/**
 * A plugin as a shareable file: a ZIP holding a `plugin.json` manifest and the
 * Python source beside it.
 *
 * ```
 * cipher.wmplugin
 * ├── plugin.json
 * ├── main.py
 * ├── helpers.py           API 5: any other module, `import helpers`
 * ├── lib/__init__.py      API 5: a package
 * └── assets/logo.png      API 5: pictures and data, `ui.image("assets/logo.png")`
 * ```
 *
 * A Clink action (`.clinkext`, see [ClinkAction]) is read through the same
 * door: converted to this shape first, then held to every rule below.
 *
 * A ZIP rather than a single JSON file with the source embedded as a string,
 * because embedding would make authors escape every quote and newline in their
 * own code — the most beginner-hostile choice available — and would foreclose
 * ever shipping a second module beside `main.py`.
 *
 * **Entry names are never used as paths**, exactly as in
 * [com.wasimaster.wmkeyboard.core.stickers.StickerPackFile]: every entry is
 * spilled to a name this importer chooses, and the manifest's `entry` field is
 * only ever a key into a map of names. Neither a `../` entry name nor a `../`
 * in the manifest can reach outside the staging directory.
 *
 * Unlike the data addon formats, nothing here is repaired. A sticker pack that
 * loses one image is still a sticker pack; a plugin whose manifest this build
 * only half understands is code running on assumptions nobody checked, so every
 * problem is a refusal with a reason attached.
 */
object PluginFile {

    const val FORMAT = PluginManifestCodec.FORMAT
    const val VERSION = PluginManifestCodec.VERSION
    const val FILE_EXTENSION = "wmplugin"
    const val MIME_TYPE = "application/zip"

    /**
     * What the import picker accepts. Permissive on purpose: providers report a
     * custom extension as `application/octet-stream` as often as not, and the
     * real check is the manifest inside.
     */
    val IMPORT_MIME_TYPES = arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )

    const val MANIFEST = "plugin.json"

    /**
     * Source cap. Generous for a panel tool, and low enough that compiling
     * it — which happens in Java, where the instruction budget cannot reach — is
     * bounded to milliseconds.
     */
    const val MAX_SCRIPT_BYTES = 256 * 1024

    /** Zip-bomb guards: nothing legitimate comes close to either. */
    const val MAX_ENTRIES = 64
    const val MAX_TOTAL_BYTES = 4L * 1024 * 1024

    /** One asset. */
    const val MAX_ASSET_BYTES = 512 * 1024

    const val ASSETS_PREFIX = "assets/"

    private val ASSET_NAME = Regex("^[a-z0-9][a-z0-9_.-]{0,63}\\.(png|webp|jpg|jpeg|svg|json|txt)$")
    private val MODULE_PART = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")

    /** Whether [name] (after `assets/`) is a name an asset may have: one flat, lowercase file name. */
    fun isAssetName(name: String): Boolean = ASSET_NAME.matches(name) && ".." !in name

    /** Whether [entry] names a module `import` can reach: identifiers between slashes, ending `.py`. */
    fun isModuleName(entry: String): Boolean {
        if (!entry.endsWith(".py")) return false
        val parts = entry.removeSuffix(".py").split('/')
        return parts.size <= 4 && parts.all { MODULE_PART.matches(it) }
    }

    /** Manifests are small; this is already an order of magnitude of headroom. */
    private const val MAX_MANIFEST_BYTES = 64 * 1024

    /**
     * "Demo-1.2.wmplugin": the plugin's own version goes in the name (#970), so
     * two releases of one plugin sitting in Downloads can be told apart.
     */
    fun fileName(manifest: PluginManifest): String {
        val safe = Regex("[^\\p{L}\\p{N} ._-]")
        val stem = manifest.name.ifBlank { manifest.id }
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
            .trim()
            .ifBlank { "plugin" }
        val version = manifest.pluginVersion.replace(safe, "").trim('.', ' ')
        return if (version.isEmpty()) "$stem.$FILE_EXTENSION" else "$stem-$version.$FILE_EXTENSION"
    }

    /** Writes a plugin archive: the plugin editor's export, the tests, and the repository tooling. */
    fun write(
        out: OutputStream,
        manifest: PluginManifest,
        script: String,
        modules: Map<String, String> = emptyMap(),
        assets: Map<String, ByteArray> = emptyMap(),
    ) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(PluginManifestCodec.encode(manifest).toByteArray())
            zip.closeEntry()
            val entry = manifest.entry.ifBlank { PluginManifestCodec.DEFAULT_ENTRY }
            zip.putNextEntry(ZipEntry(entry))
            zip.write(script.toByteArray())
            zip.closeEntry()
            for ((name, source) in modules) {
                if (name == entry || !isModuleName(name)) continue
                zip.putNextEntry(ZipEntry(name))
                zip.write(source.toByteArray())
                zip.closeEntry()
            }
            for ((name, bytes) in assets) {
                if (!isAssetName(name)) continue
                zip.putNextEntry(ZipEntry(ASSETS_PREFIX + name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads only the manifest out of [input], for the pre-install disclosure on
     * the addon detail page.
     *
     * Deliberately never looks at the script: showing the user what a plugin
     * says it can do should not involve touching the code that would do it.
     */
    fun readManifest(input: InputStream): PluginManifestResult {
        val text = runCatching {
            ZipInputStream(ClinkAction.normalise(input, MAX_TOTAL_BYTES.toInt())).use { zip ->
                var count = 0
                var found: String? = null
                var reading = true
                while (reading && found == null) {
                    val entry = zip.nextEntry
                    when {
                        entry == null -> reading = false
                        entry.isDirectory -> Unit
                        ++count > MAX_ENTRIES -> reading = false
                        entry.name == MANIFEST -> found = readCapped(zip, MAX_MANIFEST_BYTES)
                    }
                }
                found
            }
        }.getOrNull() ?: return PluginManifestResult.NotAPlugin
        return PluginManifestCodec.read(text)
    }

    /**
     * Reads a whole plugin out of [input] without installing it, refusing
     * everything [import] refuses. What the plugin editor opens a file with.
     *
     * [import] is this followed by [PluginStore.adopt], so a file the editor can
     * open is exactly a file the store would install.
     */
    @Suppress("ReturnCount")
    fun read(input: InputStream): PluginReadResult {
        val unpacked = runCatching { unpack(ClinkAction.normalise(input, MAX_TOTAL_BYTES.toInt())) }.getOrNull()
            ?: return PluginReadResult.Failed

        val manifestText = unpacked.manifest ?: return PluginReadResult.NotAPlugin
        val accepted = when (val result = PluginManifestCodec.read(manifestText)) {
            is PluginManifestResult.Ok -> result
            is PluginManifestResult.Rejected -> return PluginReadResult.Rejected(result.reasonText)
            PluginManifestResult.NotAPlugin -> return PluginReadResult.NotAPlugin
        }
        val manifest = accepted.manifest

        val source = unpacked.lookUp(manifest.entry)
            ?: return PluginReadResult.Rejected(
                PluginText.of(R.string.core_plugins_reject_missing_script, manifest.name, manifest.entry),
            )
        if (source.isEmpty()) {
            return PluginReadResult.Rejected(
                PluginText.of(R.string.core_plugins_reject_empty_script, manifest.name),
            )
        }
        if (source.size > MAX_SCRIPT_BYTES) {
            return PluginReadResult.Rejected(
                PluginText.of(
                    R.string.core_plugins_reject_script_too_large,
                    manifest.name,
                    MAX_SCRIPT_BYTES / 1024,
                ),
            )
        }
        val script = runCatching { source.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            ?: return PluginReadResult.Rejected(
                PluginText.of(R.string.core_plugins_reject_bad_encoding, manifest.name),
            )
        if (manifest.apiVersion < PluginManifestCodec.PACKAGE_API) {
            return PluginReadResult.Ok(manifest, accepted.permissions, script)
        }
        if (unpacked.overflowed) {
            return PluginReadResult.Rejected(
                PluginText.of(R.string.core_plugins_reject_archive_too_large, manifest.name, (MAX_TOTAL_BYTES / 1024).toInt()),
            )
        }
        val modules = LinkedHashMap<String, String>()
        val assets = LinkedHashMap<String, ByteArray>()
        val entryName = unpacked.nameOf(manifest.entry)
        for ((name, bytes) in unpacked.files) {
            when {
                name == entryName -> Unit
                name.startsWith(ASSETS_PREFIX) -> {
                    val asset = name.removePrefix(ASSETS_PREFIX)
                    if (!isAssetName(asset) || bytes.size > MAX_ASSET_BYTES) {
                        return PluginReadResult.Rejected(PluginText.of(R.string.core_plugins_reject_bad_module, manifest.name))
                    }
                    assets[asset] = bytes
                }
                name.endsWith(".py") -> {
                    val text = bytes.takeIf { isModuleName(name) && it.size <= MAX_SCRIPT_BYTES }
                        ?.let { runCatching { it.decodeToString(throwOnInvalidSequence = true) }.getOrNull() }
                        ?: return PluginReadResult.Rejected(PluginText.of(R.string.core_plugins_reject_bad_module, manifest.name))
                    modules[name] = text
                }
                // Anything else (a README, a licence, test.py's fixtures) is left out.
                else -> Unit
            }
        }
        return PluginReadResult.Ok(manifest, accepted.permissions, script, modules, assets)
    }

    /**
     * Reads a plugin out of [input] and writes it into [store] under the id its
     * manifest declares, replacing any install with the same id.
     */
    fun import(
        input: InputStream,
        store: PluginStore,
        now: Long = System.currentTimeMillis(),
    ): PluginImportResult = when (val outcome = read(input)) {
        is PluginReadResult.Ok -> when (
            val adopted = store.adopt(outcome.manifest, outcome.script, now, modules = outcome.modules, assets = outcome.assets)
        ) {
            is PluginAdoptResult.Adopted -> PluginImportResult.Imported(adopted.plugin, adopted.replaced)
            PluginAdoptResult.TooManyPlugins -> PluginImportResult.TooManyPlugins
            PluginAdoptResult.Failed -> PluginImportResult.Failed
        }

        PluginReadResult.NotAPlugin -> PluginImportResult.NotAPlugin
        is PluginReadResult.Rejected -> PluginImportResult.Rejected(outcome.reasonText)
        PluginReadResult.Failed -> PluginImportResult.Failed
    }

    /**
     * The archive read into memory: entry name -> bytes, plus the manifest as
     * text.
     *
     * Held in memory rather than spilled to disk, unlike the sticker importer:
     * the whole archive is capped at [MAX_TOTAL_BYTES], which is small enough
     * that a staging directory would be more moving parts than it is worth.
     */
    private class Unpacked(val files: Map<String, ByteArray>, val manifest: String?, val overflowed: Boolean) {

        /** The archive name [lookUp] resolves [entry] to. */
        fun nameOf(entry: String): String? {
            if (entry in files) return entry
            val tail = entry.substringAfterLast('/').substringAfterLast('\\')
            if (tail.isEmpty()) return null
            if (tail in files) return tail
            return files.keys.firstOrNull { it.substringAfterLast('/') == tail }
        }

        /**
         * Finds the entry the manifest names, trying the exact name and then its
         * bare tail, so `main.py` and `src/main.py` both resolve.
         *
         * Safe because the value never becomes a path: the result is a byte
         * array this importer already read.
         */
        fun lookUp(entry: String): ByteArray? {
            files[entry]?.let { return it }
            val tail = entry.substringAfterLast('/').substringAfterLast('\\')
            if (tail.isEmpty()) return null
            return files[tail] ?: files.entries
                .firstOrNull { it.key.substringAfterLast('/') == tail }
                ?.value
        }
    }

    private fun unpack(input: InputStream): Unpacked {
        val files = LinkedHashMap<String, ByteArray>()
        var manifest: String? = null
        var overflowed = false
        ZipInputStream(input.buffered()).use { zip ->
            var count = 0
            var total = 0L
            var reading = true
            while (reading) {
                val entry = zip.nextEntry
                val remaining = MAX_TOTAL_BYTES - total
                when {
                    entry == null -> reading = false
                    // Directories cost nothing and don't count against the cap.
                    entry.isDirectory -> Unit
                    ++count > MAX_ENTRIES -> {
                        overflowed = true
                        reading = false
                    }
                    entry.name == MANIFEST -> manifest = readCapped(zip, MAX_MANIFEST_BYTES)
                    remaining <= 0L -> {
                        overflowed = true
                        reading = false
                    }
                    else -> {
                        // One byte past the cap, so an oversized script can be
                        // *reported* as oversized rather than silently truncated
                        // into a confusing syntax error.
                        val perEntry = minOf(remaining, maxOf(MAX_SCRIPT_BYTES, MAX_ASSET_BYTES) + 1L).toInt()
                        val bytes = readCappedBytes(zip, perEntry)
                        total += bytes.size
                        files[entry.name] = bytes
                    }
                }
            }
        }
        return Unpacked(files, manifest, overflowed)
    }

    private fun readCapped(zip: ZipInputStream, max: Int): String =
        readCappedBytes(zip, max).decodeToString()

    /**
     * Reads at most [max] bytes of the current entry. A declared size is never
     * trusted — the count comes from what was actually read.
     */
    private fun readCappedBytes(zip: ZipInputStream, max: Int): ByteArray {
        val out = ByteArray(max)
        var filled = 0
        val buffer = ByteArray(8 * 1024)
        while (filled < max) {
            val n = zip.read(buffer, 0, minOf(buffer.size, max - filled))
            if (n <= 0) break
            System.arraycopy(buffer, 0, out, filled, n)
            filled += n
        }
        return out.copyOf(filled)
    }
}

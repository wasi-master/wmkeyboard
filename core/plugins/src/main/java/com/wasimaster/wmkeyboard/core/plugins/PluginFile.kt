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
    /** A plugin [PluginFile.import] would accept, with its script. */
    data class Ok(
        val manifest: PluginManifest,
        val permissions: List<PluginPermission>,
        val script: String,
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
 * Lua source beside it.
 *
 * ```
 * cipher.wmplugin
 * ├── plugin.json
 * └── main.lua
 * ```
 *
 * A Clink action (`.clinkext`, see [ClinkAction]) is read through the same
 * door: converted to this shape first, then held to every rule below.
 *
 * A ZIP rather than a single JSON file with the source embedded as a string,
 * because embedding would make authors escape every quote and newline in their
 * own code — the most beginner-hostile choice available — and would foreclose
 * ever shipping a second module beside `main.lua`.
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
     * Lua source cap. Generous for a panel tool, and low enough that compiling
     * it — which happens in Java, where the instruction budget cannot reach — is
     * bounded to milliseconds.
     */
    const val MAX_SCRIPT_BYTES = 256 * 1024

    /** Zip-bomb guards: nothing legitimate comes close to either. */
    private const val MAX_ENTRIES = 16
    private const val MAX_TOTAL_BYTES = 1L * 1024 * 1024

    /** Manifests are small; this is already an order of magnitude of headroom. */
    private const val MAX_MANIFEST_BYTES = 64 * 1024

    /**
     * The first byte of a precompiled Lua chunk (`ESC "Lua"`).
     *
     * The sandbox already cannot load bytecode — it installs no undumper, so
     * every load path is source-only — but refusing it here as well means a
     * binary payload is rejected at import with an explanation, rather than
     * failing later with something obscure about a missing undumper.
     */
    private const val LUA_SIGNATURE = 0x1B.toByte()

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
    fun write(out: OutputStream, manifest: PluginManifest, script: String) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(PluginManifestCodec.encode(manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(manifest.entry.ifBlank { PluginManifestCodec.DEFAULT_ENTRY }))
            zip.write(script.toByteArray())
            zip.closeEntry()
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
        if (manifest.language == PluginManifestCodec.LANGUAGE_LUA && source[0] == LUA_SIGNATURE) {
            return PluginReadResult.Rejected(
                PluginText.of(R.string.core_plugins_reject_compiled_lua, manifest.name),
            )
        }
        val script = runCatching { source.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            ?: return PluginReadResult.Rejected(
                PluginText.of(R.string.core_plugins_reject_bad_encoding, manifest.name),
            )
        return PluginReadResult.Ok(manifest, accepted.permissions, script)
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
        is PluginReadResult.Ok -> when (val adopted = store.adopt(outcome.manifest, outcome.script, now)) {
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
    private class Unpacked(val files: Map<String, ByteArray>, val manifest: String?) {

        /**
         * Finds the entry the manifest names, trying the exact name and then its
         * bare tail, so `main.lua` and `src/main.lua` both resolve.
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
        val files = HashMap<String, ByteArray>()
        var manifest: String? = null
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
                    ++count > MAX_ENTRIES -> reading = false
                    entry.name == MANIFEST -> manifest = readCapped(zip, MAX_MANIFEST_BYTES)
                    remaining <= 0L -> reading = false
                    else -> {
                        // One byte past the cap, so an oversized script can be
                        // *reported* as oversized rather than silently truncated
                        // into a confusing syntax error.
                        val perEntry = minOf(remaining, MAX_SCRIPT_BYTES + 1L).toInt()
                        val bytes = readCappedBytes(zip, perEntry)
                        total += bytes.size
                        files[entry.name] = bytes
                    }
                }
            }
        }
        return Unpacked(files, manifest)
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

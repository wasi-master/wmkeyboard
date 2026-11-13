package com.wasimaster.wmkeyboard.core.snippets.espanso

import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads an Espanso package archive.
 *
 * A package is `package.yml` (a match file), `_manifest.yml` (its metadata) and
 * a `README.md`, and it reaches a phone as a zip in one of two shapes: the one
 * the Espanso Hub attaches to its releases, and the one somebody makes by
 * zipping a package directory, where everything sits under `<name>/<version>/`.
 * Both are accepted, so this looks for `package.yml` at any shallow depth and
 * takes the manifest from beside it.
 *
 * Nothing here uses an entry name as a file path. Entries are read into memory
 * under a cap and nothing is written to disk, so a maliciously named entry has
 * nowhere to escape to. That includes the pictures an `image_path` match
 * inserts: they are found by file name among the archive's own entries and
 * handed back as bytes, and the store names them itself.
 */
object EspansoPackage {

    /** Entries scanned before the archive is judged to be something else. */
    private const val MAX_ENTRIES = 400

    /** Total uncompressed bytes read, across the text entries. */
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024

    /**
     * Total uncompressed bytes of pictures kept. A budget of its own, so a
     * package whose pictures come first in the archive still has room left
     * for its match file.
     */
    private const val MAX_IMAGE_TOTAL_BYTES = 16L * 1024 * 1024

    /** Most pictures one package may bring. */
    private const val MAX_IMAGES = 64

    /** What a picture entry is called. The bytes are checked again before use. */
    private val IMAGE_FILE = Regex("""(?i).+\.(png|jpe?g|gif|webp)""")

    /** Uncompressed bytes read from any single entry. */
    private const val MAX_ENTRY_BYTES = EspansoFile.MAX_BYTES.toLong()

    /** How deep `package.yml` may sit before this stops looking. */
    private const val MAX_DEPTH = 3

    private const val MATCHES = "package.yml"
    private const val MANIFEST = "_manifest.yml"

    /** The first four bytes of any zip archive. */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** True when [head] opens a zip archive. */
    fun looksLikeZip(head: ByteArray): Boolean =
        head.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { head[it] == ZIP_MAGIC[it] }

    /**
     * Reads the package in [input], or null when it holds no match file.
     *
     * [name] is the archive's own file name, used to name the folder when the
     * manifest has no title of its own.
     */
    fun read(input: InputStream, name: String): EspansoImport? {
        var matches: String? = null
        var manifest: String? = null
        var matchesDir: String? = null
        var total = 0L
        var seen = 0
        // By lower-cased file name: an `image_path` names a place on the
        // author's computer, and the file name is the part that survives.
        val images = HashMap<String, ByteArray>()
        var imageTotal = 0L

        ZipInputStream(input).use { zip ->
            while (seen++ < MAX_ENTRIES && total < MAX_TOTAL_BYTES) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.replace('\\', '/')
                val file = path.substringAfterLast('/')
                val wanted = !entry.isDirectory && path.count { it == '/' } <= MAX_DEPTH && when {
                    file.equals(MATCHES, ignoreCase = true) -> matches == null
                    file.equals(MANIFEST, ignoreCase = true) -> manifest == null
                    else -> false
                }
                val picture = !wanted && !entry.isDirectory && images.size < MAX_IMAGES &&
                    imageTotal < MAX_IMAGE_TOTAL_BYTES &&
                    path.count { it == '/' } <= MAX_DEPTH + 1 && IMAGE_FILE.matches(file) &&
                    file.lowercase() !in images
                if (picture) {
                    val room = minOf(SnippetStore.MAX_IMAGE_BYTES.toLong(), MAX_IMAGE_TOTAL_BYTES - imageTotal)
                    val bytes = zip.readAtMost(room + 1)
                    // One byte over the room means it did not fit; a cut-off
                    // picture is worse than none.
                    if (bytes.size <= room) {
                        imageTotal += bytes.size
                        images[file.lowercase()] = bytes
                    }
                }
                if (wanted) {
                    val bytes = zip.readAtMost(MAX_ENTRY_BYTES)
                    total += bytes.size
                    if (file.equals(MATCHES, ignoreCase = true)) {
                        matches = bytes.decodeToString()
                        matchesDir = path.substringBeforeLast('/', missingDelimiterValue = "")
                    } else {
                        manifest = bytes.decodeToString()
                    }
                }
            }
        }

        val text = matches ?: return null
        // A manifest from a different directory than the match file describes a
        // different package, so it is only trusted when the two sit together.
        // Getting the folder name from the file name is the lesser mistake.
        val title = manifest
            ?.takeIf { matchesDir != null }
            ?.let(::titleOf)
        return EspansoFile.read(text, name, folderName = title) { imagePath ->
            images[imagePath.replace('\\', '/').substringAfterLast('/').lowercase()]
        }
    }

    /** The `title` a manifest declares, else its `name`, else null. */
    private fun titleOf(manifest: String): String? {
        val map = EspansoYaml.asMap(EspansoYaml.load(manifest, EspansoFile.MAX_BYTES)) ?: return null
        val title = EspansoYaml.asText(map["title"])?.trim()?.takeIf { it.isNotEmpty() }
        return title ?: EspansoYaml.asText(map["name"])?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Reads at most [limit] bytes.
     *
     * Counted from what is actually read rather than from the entry's declared
     * size, which an archive is free to lie about.
     */
    private fun InputStream.readAtMost(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var read = 0L
        while (read < limit) {
            val n = read(buffer, 0, minOf(buffer.size.toLong(), limit - read).toInt())
            if (n <= 0) break
            out.write(buffer, 0, n)
            read += n
        }
        return out.toByteArray()
    }
}

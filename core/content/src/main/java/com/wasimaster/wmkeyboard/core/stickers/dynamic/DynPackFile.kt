package com.wasimaster.wmkeyboard.core.stickers.dynamic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The `.wmdynstickers` file: a zip holding one pack.
 *
 * ```
 * pack.json              {"format": "wmkeyboard-dynstickers", "version": 1, "pack": {…}}
 * templates/<id>.json    Lottie documents
 * scenes/<id>.json       the builder's sources, when the builder made them
 * fonts/<file>.ttf       fonts the documents name, by family
 * images/<file>          pictures the documents use
 * ```
 *
 * The manifest is [DynPackMeta]. Paths inside it are relative to the zip's
 * root and never leave it; a template whose file is missing is dropped on
 * import rather than failing the pack.
 */
object DynPackFile {

    const val FORMAT = "wmkeyboard-dynstickers"
    const val FORMAT_VERSION = 1
    const val FILE_EXTENSION = "wmdynstickers"
    const val MIME = "application/zip"
    const val MANIFEST = "pack.json"

    /** The feed index format: a list of packs to download. */
    const val FEED_FORMAT = "wmkeyboard-dynstickers-feed"

    @Serializable
    data class Manifest(
        val format: String = FORMAT,
        val version: Int = FORMAT_VERSION,
        val pack: DynPackMeta,
    )

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = false
        coerceInputValues = true
    }

    /** The pack a `pack.json` describes, or null when it is not one of ours. */
    fun readManifest(text: String): DynPackMeta? {
        val manifest = runCatching { json.decodeFromString(Manifest.serializer(), text) }.getOrNull() ?: return null
        if (manifest.format != FORMAT) return null
        return manifest.pack
    }

    fun writeManifest(pack: DynPackMeta): String = json.encodeToString(Manifest.serializer(), Manifest(pack = pack))

    /** Whether [text] looks like our manifest, cheaply, for routing a picked file. */
    fun isManifest(text: String): Boolean = text.contains("\"$FORMAT\"")

    /**
     * Writes the pack in [dir] (laid out as above) to [out] as a zip. Only the
     * files the manifest names are written, so nothing else in the directory
     * (a staged download, the editor's scratch) leaves the device.
     */
    fun write(dir: File, pack: DynPackMeta, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            val exported = pack.copy(source = PackSource.USER, feedId = null, sourceUrl = null)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(writeManifest(exported).toByteArray())
            zip.closeEntry()
            val names = LinkedHashSet<String>()
            pack.templates.forEach { template ->
                template.file.takeIf { it.isNotBlank() }?.let(names::add)
                template.scene?.let(names::add)
            }
            pack.fonts.forEach { names += it.file }
            imagesOf(dir).forEach { names += it }
            for (name in names) {
                val file = resolve(dir, name) ?: continue
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** `images/…` files in [dir], as entry names. */
    private fun imagesOf(dir: File): List<String> =
        File(dir, IMAGES_DIR).listFiles().orEmpty().filter { it.isFile }.map { "$IMAGES_DIR/${it.name}" }

    /**
     * [path] inside [dir], or null when it would land outside it: a manifest
     * is untrusted, and `../` must not reach the rest of the app's files.
     */
    fun resolve(dir: File, path: String): File? {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\')) return null
        val target = File(dir, path)
        val root = dir.canonicalFile
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    const val TEMPLATES_DIR = "templates"
    const val SCENES_DIR = "scenes"
    const val FONTS_DIR = "fonts"
    const val IMAGES_DIR = "images"
}

/**
 * The font list template feeds ship beside their fonts: an Android-style
 * `fonts.xml` `familyset`, mapping family names to files.
 *
 * ```
 * <familyset><family name="DynaPuff"><font weight="400" style="normal">DynaPuff-Black.ttf</font></family></familyset>
 * ```
 *
 * Read with patterns rather than an XML parser: the file is tiny, and this
 * way it reads the same in a unit test as on a phone.
 */
object FontsXml {

    /** Family name → font file names, in document order. */
    fun parse(text: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (family in FAMILY.findAll(text)) {
            val name = family.groupValues[1].trim()
            if (name.isEmpty()) continue
            val files = FONT.findAll(family.groupValues[2]).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }
            val list = out.getOrPut(name) { ArrayList() }
            files.forEach { if (it !in list) list += it }
        }
        return out
    }

    private val FAMILY = Regex("<family[^>]*\\bname=\"([^\"]*)\"[^>]*>(.*?)</family>", RegexOption.DOT_MATCHES_ALL)
    private val FONT = Regex("<font[^>]*>([^<]*)</font>")
}

/**
 * A feed link's index: where a pack (or several) is downloaded from.
 *
 * Two shapes are read. Ours, `{"format": "wmkeyboard-dynstickers-feed",
 * "name": …, "packs": [{"id", "name", "url", "version", "sha256", "size"}]}`,
 * points at `.wmdynstickers` files. The other is the manifest some template
 * sets are published with: `base_download_url` plus `packs[]` of zips
 * (`download_urls`, `md5_checksum`, `size`, `label`), one holding the
 * documents and one the fonts; its zips are assembled into a single pack.
 */
object TemplateFeed {

    /** One file to download. [checksum] is hex; [algorithm] "SHA-256" or "MD5", or null when unpinned. */
    data class Download(
        val url: String,
        val name: String,
        val label: String,
        val checksum: String?,
        val algorithm: String?,
        val size: Long,
    )

    /** What a feed index lists. [assembled] means every download is a part of one pack, not a pack each. */
    data class Index(
        val name: String,
        val version: String,
        val downloads: List<Download>,
        val assembled: Boolean,
    )

    fun parse(text: String, indexUrl: String): Index? =
        runCatching { parseOrThrow(text, indexUrl) }.getOrNull()?.takeIf { it.downloads.isNotEmpty() }

    private fun parseOrThrow(text: String, indexUrl: String): Index? {
        val root = DynPackFile.json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: return null
        val format = (root["format"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val packs = root["packs"] as? kotlinx.serialization.json.JsonArray ?: return null
        if (format == DynPackFile.FEED_FORMAT) {
            val downloads = packs.mapNotNull { element ->
                val pack = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val url = pack.str("url") ?: return@mapNotNull null
                Download(
                    url = resolveUrl(indexUrl, url) ?: return@mapNotNull null,
                    name = pack.str("name") ?: pack.str("id").orEmpty(),
                    label = pack.str("id").orEmpty(),
                    checksum = pack.str("sha256"),
                    algorithm = pack.str("sha256")?.let { "SHA-256" },
                    size = pack.long("size"),
                )
            }
            return Index(root.str("name").orEmpty(), root.str("version").orEmpty(), downloads, assembled = false)
        }
        val base = root.str("base_download_url") ?: return null
        val downloads = packs.mapNotNull { element ->
            val pack = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val first = (pack["download_urls"] as? kotlinx.serialization.json.JsonArray)
                ?.firstNotNullOfOrNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: return@mapNotNull null
            val md5 = pack.str("md5_checksum")
            Download(
                url = resolveUrl(base.trimEnd('/') + "/", first) ?: return@mapNotNull null,
                name = pack.str("label") ?: first.substringAfterLast('/'),
                label = pack.str("label").orEmpty(),
                checksum = md5,
                algorithm = md5?.let { "MD5" },
                size = pack.long("compressed_size").takeIf { it > 0 } ?: pack.long("size"),
            )
        }
        // The version is the dated folder the files sit in, when there is one.
        val version = downloads.firstOrNull()?.url?.substringBeforeLast('/')?.substringAfterLast('/').orEmpty()
        return Index("", version, downloads, assembled = true)
    }

    /**
     * [path] against [base], https only. A feed is untrusted, so a link that
     * would download over plain http, or from a `file:`/`content:` address,
     * is dropped.
     */
    fun resolveUrl(base: String, path: String): String? {
        val absolute = runCatching { java.net.URI(base).resolve(path.trim()).toString() }.getOrNull() ?: return null
        return absolute.takeIf { it.startsWith("https://") }
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    private fun kotlinx.serialization.json.JsonObject.long(key: String): Long =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
}

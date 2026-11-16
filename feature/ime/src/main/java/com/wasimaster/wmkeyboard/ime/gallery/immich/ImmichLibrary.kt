package com.wasimaster.wmkeyboard.ime.gallery.immich

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.wasimaster.wmkeyboard.core.media.gallery.AlbumKind
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryAlbum
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryFilter
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryItem
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryKind
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.core.settings.GallerySettings
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * The user's Immich server as the gallery sees it (#1205): its timeline and
 * albums as [GalleryAlbum]s, their pictures as [GalleryItem]s the grid draws
 * like the phone's own, and the two moves that need the server: fetching an
 * original before it is sent or edited, and uploading a phone picture.
 *
 * An Immich item's [GalleryItem.sourceUri] is `immich://asset/<id>` and its
 * row id a large positive number made from the asset's UUID, so it never
 * meets a MediaStore id or a picker's negative one. The months of each album
 * are listed once and filled in as the grid pages down; [forget] drops that
 * when the panel opens again, so new pictures on the server show up.
 *
 * Videos are listed too, with their length: a video is fetched whole before
 * it plays in the viewer or is sent, the same fetch a picture's original
 * takes, so it goes through the network log like every other call.
 */
internal object ImmichLibrary {

    /** The server and key in use: what the cache below was filled from. */
    private data class Config(val url: String, val key: String)

    private class AlbumCache(val buckets: List<ImmichClient.Bucket>) {
        val loaded = ArrayList<GalleryItem>()
        var next = 0
        val done: Boolean get() = next >= buckets.size
    }

    private var config: Config? = null
    private var client: ImmichClient? = null
    private val cache = HashMap<String, AlbumCache>()

    /**
     * Saving data off Wi-Fi (#1374), set by the panel as it opens and before
     * each fetch: the grid shows only thumbnails already on the phone, a
     * tapped picture comes down as the server's preview (about 1440 px), and
     * an original, a video or an upload waits unless the user asks for it.
     */
    @Volatile
    var lean: Boolean = false

    /** Where the server's thumbnails and previews are kept on the phone, so they show again without the network. */
    @Volatile
    private var pictureDir: File? = null
    private var writesSincePrune = 0

    /** Names the picture cache's folder; the panel calls it once. */
    fun attach(context: Context) {
        if (pictureDir == null) pictureDir = File(File(context.cacheDir, "media"), PICTURE_DIR)
    }

    fun configured(s: GallerySettings): Boolean = s.immichUrl.isNotBlank() && s.immichKey.isNotBlank()

    @Synchronized
    fun client(s: GallerySettings): ImmichClient? {
        if (!configured(s)) return null
        val c = Config(s.immichUrl.trim(), s.immichKey.trim())
        if (c != config) {
            config = c
            client = ImmichClient(c.url, c.key).takeIf { it.base != null }
            cache.clear()
        }
        return client
    }

    /** Lists afresh next time: the panel opened again. */
    @Synchronized
    fun forget() {
        cache.clear()
    }

    // ---- Albums --------------------------------------------------------------

    /** The whole timeline first, then the albums by name. Throws when the server cannot be reached. */
    fun albums(s: GallerySettings): List<GalleryAlbum> {
        val c = client(s) ?: return emptyList()
        val timeline = c.buckets(null)
        val first = timeline.firstOrNull()?.let { runCatching { c.bucket(it.key, null) }.getOrNull() }
            ?.firstOrNull { it.image && !it.trashed }
        val all = GalleryAlbum(
            kind = AlbumKind.IMMICH,
            id = TIMELINE,
            name = null,
            count = timeline.sumOf { it.count },
            cover = first?.let { uriOf(it.id) },
        )
        val albums = runCatching { c.albums() }.getOrDefault(emptyList())
            .sortedBy { it.name.lowercase() }
            .map { a ->
                GalleryAlbum(AlbumKind.IMMICH_ALBUM, a.id, a.name, a.count, a.coverId?.let(::uriOf))
            }
        return listOf(all) + albums
    }

    fun isRemote(album: GalleryAlbum): Boolean = album.kind == AlbumKind.IMMICH || album.kind == AlbumKind.IMMICH_ALBUM

    /**
     * Rows [offset] up to [offset] + [limit] of [album], newest first, asking
     * the server for as many months as that takes. Pictures and videos; the
     * server tells no GIF apart, so a filter for GIFs finds nothing here.
     */
    fun page(s: GallerySettings, album: GalleryAlbum, filter: GalleryFilter, offset: Int, limit: Int): List<GalleryItem> {
        if (filter == GalleryFilter.GIFS) return emptyList()
        val c = client(s) ?: return emptyList()
        val albumId = album.id.takeIf { album.kind == AlbumKind.IMMICH_ALBUM }
        // Each filter keeps its own rows, so an offset counts the rows it shows.
        val key = (albumId ?: TIMELINE) + "|" + filter.name
        val entry = synchronized(this) { cache[key] } ?: AlbumCache(c.buckets(albumId)).also {
            synchronized(this) { cache[key] = it }
        }
        while (entry.loaded.size < offset + limit && !entry.done) {
            val bucket = entry.buckets[entry.next]
            val assets = c.bucket(bucket.key, albumId)
            entry.loaded += assets.filter { !it.trashed }
                .map { item(it, album.name.orEmpty()) }
                .filter { filter.accepts(it.kind) }
            entry.next++
        }
        if (offset >= entry.loaded.size) return emptyList()
        return entry.loaded.subList(offset, minOf(entry.loaded.size, offset + limit)).toList()
    }

    private fun item(a: ImmichClient.Asset, albumName: String): GalleryItem {
        return GalleryItem(
            id = rowId(a.id),
            mime = if (a.image) "image/jpeg" else "video/mp4",
            kind = if (a.image) GalleryKind.IMAGE else GalleryKind.VIDEO,
            name = "",
            // The timeline gives the shape only; the size comes with the original.
            width = (a.ratio * RATIO_SCALE).toInt(),
            height = RATIO_SCALE,
            orientation = 0,
            size = 0,
            durationMs = a.durationMs,
            dateMs = a.createdMs,
            bucketId = REMOTE_BUCKET,
            bucketName = albumName,
            favorite = false,
            sourceUri = uriOf(a.id),
        )
    }

    // ---- Pictures --------------------------------------------------------------

    fun uriOf(assetId: String): Uri = Uri.Builder().scheme(GalleryItem.REMOTE_SCHEME).authority("asset").appendPath(assetId).build()

    /** The asset id an `immich://asset/<id>` address names, or null for any other address. */
    fun assetOf(uri: Uri): String? =
        if (uri.scheme == GalleryItem.REMOTE_SCHEME && uri.authority == "asset") uri.lastPathSegment else null

    /** A positive id at or above 2^62, from the asset's UUID: clear of MediaStore's and the pickers'. */
    fun rowId(assetId: String): Long {
        val bits = runCatching { UUID.fromString(assetId).let { it.mostSignificantBits xor it.leastSignificantBits } }
            .getOrElse { assetId.hashCode().toLong() }
        return (bits and (Long.MAX_VALUE ushr 1)) or (1L shl 62)
    }

    /**
     * The server's thumbnail (or, [preview], its larger picture) for [uri],
     * from the server the albums were last listed from: the grid only shows
     * an Immich picture once the controller has done that.
     *
     * Kept on the phone once fetched (#1374). While [lean], only what is kept
     * is shown, with the small thumbnail standing in for a missing preview,
     * unless [asked]: the viewer, opened by a tap, fetches the preview.
     */
    fun picture(uri: Uri, preview: Boolean, asked: Boolean = false): ByteArray? {
        val id = assetOf(uri) ?: return null
        kept(id, preview)?.let { return it }
        if (lean && !asked) return if (preview) kept(id, preview = false) else null
        val c = synchronized(this) { client } ?: return null
        return c.thumbnail(id, preview).also { keep(id, preview, it) }
    }

    private fun keptFile(id: String, preview: Boolean): File? = pictureDir?.let { File(it, id + if (preview) ".p" else ".t") }

    private fun kept(id: String, preview: Boolean): ByteArray? {
        val f = keptFile(id, preview)?.takeIf { it.isFile && it.length() > 0 } ?: return null
        return runCatching { f.readBytes() }.getOrNull()?.also { f.setLastModified(System.currentTimeMillis()) }
    }

    private fun keep(id: String, preview: Boolean, bytes: ByteArray) {
        val f = keptFile(id, preview) ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
        }
        val prune = synchronized(this) { ++writesSincePrune >= PRUNE_EVERY }
        if (prune) prunePictures()
    }

    /** Keeps the picture cache under [PICTURE_CACHE_BYTES], the least recently shown going first. */
    private fun prunePictures() {
        synchronized(this) { writesSincePrune = 0 }
        val files = pictureDir?.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > PICTURE_CACHE_BYTES) f.delete()
        }
    }

    /**
     * [item] as a file on the phone: its original fetched into the gallery's
     * send cache (once; a second send reuses it), and the item pointing there,
     * so editing and sending treat it as they treat a picked file. Null when
     * the server would not give it.
     */
    fun fetch(context: Context, s: GallerySettings, item: GalleryItem, full: Boolean = true): GalleryItem? {
        if (!item.isRemote) return item
        val c = client(s) ?: return null
        // Saving data (#1374): the server's preview stands in for the original.
        return if (full) fetchWith(context, c, item) else previewWith(context, c, item)
    }

    /**
     * [item] as the server's preview, a JPEG about 1440 px on its long side
     * and a fraction of the original's size: what is sent or edited while
     * saving data, unless Original was picked. Null for a video, which has
     * no smaller form to send.
     */
    private fun previewWith(context: Context, c: ImmichClient, item: GalleryItem): GalleryItem? {
        if (item.isVideo) return null
        val id = item.sourceUri?.let(::assetOf) ?: return null
        val dir = File(MediaPrep.outputDir(context), "immich").apply { mkdirs() }
        val file = File(dir, "$id.preview.jpg")
        if (!file.isFile || file.length() == 0L) {
            val bytes = kept(id, preview = true) ?: runCatching { c.thumbnail(id, preview = true) }.getOrNull()
                ?.also { keep(id, true, it) } ?: return null
            if (runCatching { file.writeBytes(bytes) }.isFailure) return null
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeFile(file.path, bounds) }
        return item.copy(
            sourceUri = Uri.fromFile(file),
            mime = "image/jpeg",
            name = "IMG_${item.dateMs}.jpg",
            size = file.length(),
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
            orientation = 0,
        )
    }

    /**
     * [fetch] from the server the albums were last listed from, for the
     * viewer, which has no settings to hand: a video plays from its fetched
     * original (#1205).
     */
    fun fetchFromServer(context: Context, item: GalleryItem): GalleryItem? {
        if (!item.isRemote) return item
        // A video is its original: it waits for Wi-Fi while saving data (#1374).
        if (lean) return null
        val c = synchronized(this) { client } ?: return null
        return fetchWith(context, c, item)
    }

    private fun fetchWith(context: Context, c: ImmichClient, item: GalleryItem): GalleryItem? {
        val id = item.sourceUri?.let(::assetOf) ?: return null
        val dir = File(MediaPrep.outputDir(context), "immich").apply { mkdirs() }
        val done = dir.listFiles()?.firstOrNull { it.name.startsWith("$id.") && it.length() > 0 }
        val (file, mime, name) = if (done != null) {
            Triple(done, mimeOfExt(done.extension), done.name)
        } else {
            val part = File(dir, "$id.part")
            val original = runCatching { c.download(id, part) }.getOrElse {
                part.delete()
                return null
            }
            val ext = original.name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 1..5 }
                ?: MediaPrep.extensionFor(original.mime)
            val file = File(dir, "$id.$ext")
            if (!part.renameTo(file)) return null
            Triple(
                file,
                original.mime.takeIf { it.startsWith("image/") || it.startsWith("video/") } ?: mimeOfExt(ext),
                original.name ?: file.name,
            )
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (!item.isVideo) runCatching { android.graphics.BitmapFactory.decodeFile(file.path, bounds) }
        return item.copy(
            sourceUri = Uri.fromFile(file),
            mime = mime,
            name = name,
            size = file.length(),
            // A video keeps the shape the timeline gave it.
            width = bounds.outWidth.takeIf { it > 0 } ?: if (item.isVideo) item.width else 0,
            height = bounds.outHeight.takeIf { it > 0 } ?: if (item.isVideo) item.height else 0,
            // The original's own EXIF turn is read where the picture is decoded.
            orientation = 0,
        )
    }

    private fun mimeOfExt(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "avif" -> "image/avif"
        "dng" -> "image/x-adobe-dng"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        else -> "image/jpeg"
    }

    // ---- Delete ------------------------------------------------------------------

    /**
     * Deletes [items] from the server (#1366): into Immich's trash, see
     * [ImmichClient.delete]. Phone pictures among them are left alone. Throws
     * when the server refuses or cannot be reached; the listing is dropped on
     * success, so the album is read afresh.
     */
    fun delete(s: GallerySettings, items: List<GalleryItem>): Int {
        val c = client(s) ?: throw IOException("no server")
        val ids = items.mapNotNull { it.sourceUri?.let(::assetOf) }
        c.delete(ids)
        synchronized(this) { cache.clear() }
        ids.forEach { id -> keptFile(id, true)?.delete(); keptFile(id, false)?.delete() }
        return ids.size
    }

    // ---- Upload ------------------------------------------------------------------

    /** How an upload of several went. */
    data class UploadResult(val added: Int, val already: Int, val failed: Int, val refused: Boolean)

    /**
     * Sends each phone picture in [items] to the server as it is, metadata and
     * all: it is the user's own library. Pictures that came from the server are
     * skipped. The server's checksum test answers "already there" for a picture
     * it has, which is counted apart.
     */
    fun upload(context: Context, s: GallerySettings, items: List<GalleryItem>): UploadResult {
        val c = client(s) ?: return UploadResult(0, 0, items.size, refused = false)
        var added = 0
        var already = 0
        var failed = 0
        var refused = false
        for (item in items) {
            if (item.isRemote || item.bucketId == REMOTE_BUCKET) continue
            val uri = item.uri
            try {
                val sha1 = sha1(context, uri)
                val length = item.size.takeIf { it > 0 } ?: sizeOf(context, uri)
                val name = item.name.ifBlank { "IMG_${item.dateMs}.${MediaPrep.extensionFor(item.mime)}" }
                val modified = if (item.modifiedSec > 0) item.modifiedSec * 1000 else item.dateMs
                val r = c.upload(
                    open = { context.contentResolver.openInputStream(uri) ?: throw IOException("unreadable") },
                    length = length,
                    mime = item.mime,
                    fileName = name,
                    createdMs = item.dateMs,
                    modifiedMs = modified,
                    deviceAssetId = "$name-$length",
                    sha1 = sha1,
                )
                if (r.duplicate) already++ else added++
            } catch (e: ImmichClient.Failure) {
                failed++
                if (e.refused) refused = true
            } catch (e: Exception) {
                failed++
            }
        }
        return UploadResult(added, already, failed, refused)
    }

    private fun sha1(context: Context, uri: Uri): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-1")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        } ?: return@runCatching null
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    /** [GalleryAlbum.id] of the whole timeline. */
    const val TIMELINE = "timeline"

    /** [GalleryItem.bucketId] of every Immich row. */
    const val REMOTE_BUCKET = "immich"

    private const val RATIO_SCALE = 1000
    private const val BUFFER = 64 * 1024

    /** Under the cache's `media` folder, which About › Storage already counts. */
    const val PICTURE_DIR = "immich-pictures"
    private const val PICTURE_CACHE_BYTES = 64L * 1024 * 1024
    private const val PRUNE_EVERY = 40
}

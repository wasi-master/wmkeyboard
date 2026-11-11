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
 * Only pictures are listed: Immich videos would have to be fetched whole
 * before they could be watched or sent.
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
     * the server for as many months as that takes. Videos are left out, so a
     * filter for videos or GIFs finds nothing here.
     */
    fun page(s: GallerySettings, album: GalleryAlbum, filter: GalleryFilter, offset: Int, limit: Int): List<GalleryItem> {
        if (filter == GalleryFilter.VIDEOS || filter == GalleryFilter.GIFS) return emptyList()
        val c = client(s) ?: return emptyList()
        val albumId = album.id.takeIf { album.kind == AlbumKind.IMMICH_ALBUM }
        val key = albumId ?: TIMELINE
        val entry = synchronized(this) { cache[key] } ?: AlbumCache(c.buckets(albumId)).also {
            synchronized(this) { cache[key] = it }
        }
        while (entry.loaded.size < offset + limit && !entry.done) {
            val bucket = entry.buckets[entry.next]
            val assets = c.bucket(bucket.key, albumId)
            entry.loaded += assets.filter { it.image && !it.trashed }.map { item(it, album.name.orEmpty()) }
            entry.next++
        }
        if (offset >= entry.loaded.size) return emptyList()
        return entry.loaded.subList(offset, minOf(entry.loaded.size, offset + limit)).toList()
    }

    private fun item(a: ImmichClient.Asset, albumName: String): GalleryItem {
        return GalleryItem(
            id = rowId(a.id),
            mime = "image/jpeg",
            kind = GalleryKind.IMAGE,
            name = "",
            // The timeline gives the shape only; the size comes with the original.
            width = (a.ratio * RATIO_SCALE).toInt(),
            height = RATIO_SCALE,
            orientation = 0,
            size = 0,
            durationMs = 0,
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
     */
    fun picture(uri: Uri, preview: Boolean): ByteArray? {
        val id = assetOf(uri) ?: return null
        val c = synchronized(this) { client } ?: return null
        return c.thumbnail(id, preview)
    }

    /**
     * [item] as a file on the phone: its original fetched into the gallery's
     * send cache (once; a second send reuses it), and the item pointing there,
     * so editing and sending treat it as they treat a picked file. Null when
     * the server would not give it.
     */
    fun fetch(context: Context, s: GallerySettings, item: GalleryItem): GalleryItem? {
        if (!item.isRemote) return item
        val id = item.sourceUri?.let(::assetOf) ?: return null
        val c = client(s) ?: return null
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
            Triple(file, original.mime.takeIf { it.startsWith("image/") } ?: mimeOfExt(ext), original.name ?: file.name)
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeFile(file.path, bounds) }
        return item.copy(
            sourceUri = Uri.fromFile(file),
            mime = mime,
            name = name,
            size = file.length(),
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
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
        else -> "image/jpeg"
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
}

package com.wasimaster.wmkeyboard.core.media.gallery

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * The phone's photos and videos, as the gallery tool lists them (#708).
 *
 * One MediaStore table, `Files`, filtered to pictures and videos, so a mixed
 * album pages in one query instead of two merged ones. Every call blocks and
 * belongs on a background thread; every call answers empty rather than
 * throwing when the permission is missing or a store misbehaves, because the
 * panel's job then is to say so, not to crash the keyboard.
 */
object GalleryStore {

    /** How the read permission stands. */
    enum class Access {
        /** The whole library. */
        FULL,

        /** Android 14's "Select photos": only what the user picked for this app. */
        PARTIAL,
        NONE,
    }

    fun access(context: Context): Access {
        fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> when {
                has(Manifest.permission.READ_MEDIA_IMAGES) || has(Manifest.permission.READ_MEDIA_VIDEO) -> Access.FULL
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
                else -> Access.NONE
            }
            has(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
            else -> Access.NONE
        }
    }

    /** Whether videos can be listed: on Android 13 and up they are a grant of their own. */
    fun canReadVideos(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                    PackageManager.PERMISSION_GRANTED
                )

    /**
     * Android 13 and up with photos allowed but videos not: the library lists
     * pictures only. Granting photos for another feature (screenshots in the
     * clipboard) leaves a phone here.
     */
    fun videosWithheld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return has(Manifest.permission.READ_MEDIA_IMAGES) && !has(Manifest.permission.READ_MEDIA_VIDEO)
    }

    private val filesUri: Uri = MediaStore.Files.getContentUri("external")

    private val dateColumn: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.DATE_TAKEN else "datetaken"

    /**
     * Newest first, by when the picture was taken and, for one with no such
     * date, when it reached the phone (#860). A PNG saved from a browser, a
     * sticker or an edited copy carries no EXIF date, so its `datetaken` is
     * NULL, and SQLite sorts NULL after every date: ordered on the column
     * alone, the picture saved a minute ago landed at the bottom of Recents.
     * `date_added` is in seconds and `datetaken` in milliseconds.
     */
    private val newestFirst: String
        get() = "COALESCE($dateColumn, ${MediaStore.MediaColumns.DATE_ADDED} * 1000) DESC, " +
            "${MediaStore.Files.FileColumns._ID} DESC"

    private val projection: Array<String>
        get() = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(dateColumn)
            add(ORIENTATION)
            add(DURATION)
            add(BUCKET_ID)
            add(BUCKET_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.IS_FAVORITE)
        }.toTypedArray()

    // Spelled out rather than taken from MediaColumns: below API 29 these live
    // on the per-type column interfaces only, though the columns are there.
    private const val ORIENTATION = "orientation"
    private const val DURATION = "duration"
    private const val BUCKET_ID = "bucket_id"
    private const val BUCKET_NAME = "bucket_display_name"
    private const val RELATIVE_PATH = "relative_path"
    private const val DATA = "_data"

    /** The WHERE that keeps the `Files` table to pictures, and videos when [videos]. */
    private fun typeClause(videos: Boolean): String {
        val image = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val video = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        return if (videos) {
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($image,$video)"
        } else {
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=$image"
        }
    }

    /** The folder test for the two folder-shaped collections, on any API level. */
    private fun pathClause(vararg fragments: String): String = fragments.joinToString(" OR ") { fragment ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "$RELATIVE_PATH LIKE '%$fragment%' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%$fragment%'"
        } else {
            "$DATA LIKE '%$fragment%'"
        }
    }

    /**
     * The SQL for one album under one filter, with its arguments. Built
     * separately from the query so the rules can be read in one place.
     */
    fun selection(
        album: GalleryAlbum,
        filter: GalleryFilter,
        videos: Boolean,
        sentIds: List<Long>,
        hiddenFolders: Set<String> = emptySet(),
    ): Pair<String, Array<String>> {
        val parts = mutableListOf(typeClause(videos))
        val args = mutableListOf<String>()
        // Folders the user took out of the gallery (#970), by bucket id: gone
        // from every album, Recents and Favorites included, not just their own.
        if (hiddenFolders.isNotEmpty()) {
            parts += "$BUCKET_ID NOT IN (${hiddenFolders.joinToString(",") { "?" }})"
            args += hiddenFolders
        }
        when (filter) {
            GalleryFilter.ALL -> Unit
            GalleryFilter.PHOTOS -> parts += "${MediaStore.Files.FileColumns.MEDIA_TYPE}=" +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} AND ${MediaStore.MediaColumns.MIME_TYPE}<>'image/gif'"
            GalleryFilter.VIDEOS -> parts += "${MediaStore.Files.FileColumns.MEDIA_TYPE}=" +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
            GalleryFilter.GIFS -> parts += "${MediaStore.MediaColumns.MIME_TYPE}='image/gif'"
        }
        when (album.kind) {
            AlbumKind.RECENTS -> Unit
            AlbumKind.FAVORITES -> parts += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "${MediaStore.MediaColumns.IS_FAVORITE}=1"
            } else {
                "0"
            }
            AlbumKind.VIDEOS -> parts += "${MediaStore.Files.FileColumns.MEDIA_TYPE}=" +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
            AlbumKind.GIFS -> parts += "${MediaStore.MediaColumns.MIME_TYPE}='image/gif'"
            AlbumKind.SCREENSHOTS -> parts += pathClause("screenshot", "screen_shot", "screen-shot")
            AlbumKind.CAMERA -> parts += pathClause("DCIM/Camera")
            AlbumKind.SENT -> {
                val ids = sentIds.take(MAX_SENT_QUERY)
                parts += if (ids.isEmpty()) "0" else "${MediaStore.Files.FileColumns._ID} IN (${ids.joinToString(",")})"
            }
            AlbumKind.BUCKET -> {
                parts += "$BUCKET_ID=?"
                args += album.id
            }
            // On the user's server, not in MediaStore: the keyboard pages those itself.
            AlbumKind.IMMICH, AlbumKind.IMMICH_ALBUM -> parts += "0"
        }
        return parts.joinToString(" AND ") { "($it)" } to args.toTypedArray()
    }

    private const val MAX_SENT_QUERY = 500

    /**
     * One page of an album, newest first. [sentIds] is the keyboard's own
     * send history, used by [AlbumKind.SENT], which keeps its own order: the
     * most recent send first rather than the newest photo.
     */
    fun page(
        context: Context,
        album: GalleryAlbum,
        filter: GalleryFilter,
        offset: Int,
        limit: Int,
        videos: Boolean,
        sentIds: List<Long> = emptyList(),
        hiddenFolders: Set<String> = emptySet(),
    ): List<GalleryItem> {
        val (where, args) = selection(album, filter, videos && canReadVideos(context), sentIds, hiddenFolders)
        val items = query(context.contentResolver, where, args, newestFirst, offset, limit) { readItem(it) }
        if (album.kind != AlbumKind.SENT) return items
        val rank = sentIds.withIndex().associate { (i, id) -> id to i }
        return items.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    /** Rows by id, in the order given; ids that no longer exist are left out. */
    fun byIds(context: Context, ids: List<Long>): List<GalleryItem> {
        if (ids.isEmpty()) return emptyList()
        val where = "${typeClause(true)} AND ${MediaStore.Files.FileColumns._ID} IN (${ids.joinToString(",")})"
        val found = query(context.contentResolver, where, emptyArray(), null, 0, ids.size) { readItem(it) }
            .associateBy { it.id }
        return ids.mapNotNull { found[it] }
    }

    /**
     * Every album worth listing: the tool's collections that have something
     * in them, then the phone's folders, biggest first. One pass over a few
     * columns of the whole library, which is a few tens of milliseconds even
     * on a library of tens of thousands.
     */
    fun albums(
        context: Context,
        videos: Boolean,
        sentIds: List<Long>,
        hiddenFolders: Set<String> = emptySet(),
    ): List<GalleryAlbum> {
        val withVideos = videos && canReadVideos(context)
        val cols = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(BUCKET_ID)
            add(BUCKET_NAME)
            add(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) RELATIVE_PATH else DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.IS_FAVORITE)
        }.toTypedArray()
        val tally = AlbumTally(::uriOf)
        val sent = sentIds.toHashSet()
        runCatching {
            context.contentResolver.query(
                filesUri, cols, typeClause(withVideos), null, newestFirst,
            )
        }.getOrNull()?.use { c ->
            val id = c.getColumnIndex(MediaStore.Files.FileColumns._ID)
            val type = c.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val mime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val name = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val bucket = c.getColumnIndex(BUCKET_ID)
            val bucketName = c.getColumnIndex(BUCKET_NAME)
            val path = c.getColumnIndex(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) RELATIVE_PATH else DATA)
            val fav = c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
            while (c.moveToNext()) {
                if (hiddenFolders.isNotEmpty() && c.getStringOrEmpty(bucket) in hiddenFolders) continue
                val rowId = c.getLong(id)
                val m = c.getStringOrEmpty(mime)
                val kind = GalleryItem.kindOf(m, c.getIntOr(type, 0))
                tally.add(
                    AlbumTally.Row(
                        id = rowId,
                        kind = kind,
                        bucketId = c.getStringOrEmpty(bucket),
                        bucketName = c.getStringOrEmpty(bucketName),
                        path = c.getStringOrEmpty(path) + "/" + c.getStringOrEmpty(name),
                        favorite = fav >= 0 && c.getIntOr(fav, 0) == 1,
                        sent = rowId in sent,
                    ),
                )
            }
        }
        return tally.albums(withVideos)
    }

    /**
     * The folder a library photo lives in, as MediaStore spells it
     * ("DCIM/Camera/"), when a new picture may be written there too: Android
     * takes new pictures only under DCIM and Pictures. Null otherwise, and
     * below Android 10, where folders are not the store's to say.
     */
    fun pictureFolderOf(context: Context, item: GalleryItem): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || item.isPicked) return null
        val path = runCatching {
            context.contentResolver.query(item.uri, arrayOf(RELATIVE_PATH), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getStringOrEmpty(0) else ""
            }
        }.getOrNull().orEmpty()
        return path.takeIf { it.startsWith("DCIM/") || it.startsWith("Pictures/") }
    }

    /** The content URI of one row, under its own type's table so other apps can read it. */
    fun uriOf(id: Long, kind: GalleryKind): Uri = ContentUris.withAppendedId(
        if (kind == GalleryKind.VIDEO) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        },
        id,
    )

    /**
     * Calls [onChange] on the main thread when the library changes: a photo
     * taken, one deleted, a folder synced. Unregister with [stopWatching].
     */
    fun watch(context: Context, onChange: () -> Unit): ContentObserver {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        runCatching { context.contentResolver.registerContentObserver(filesUri, true, observer) }
        return observer
    }

    fun stopWatching(context: Context, observer: ContentObserver) {
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
    }

    private fun <T> query(
        resolver: ContentResolver,
        where: String,
        args: Array<String>,
        order: String?,
        offset: Int,
        limit: Int,
        read: (Cursor) -> T?,
    ): List<T> {
        // From Android 11 MediaStore refuses a LIMIT smuggled into the sort
        // order and wants the query-arguments Bundle instead; before that the
        // Bundle form is not honoured everywhere, and the sort-order form is.
        val cursor = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bundle = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    order?.let { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it) }
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                    putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                }
                resolver.query(filesUri, projection, bundle, null)
            } else {
                val sort = (order ?: "${MediaStore.Files.FileColumns._ID} DESC") + " LIMIT $limit OFFSET $offset"
                resolver.query(filesUri, projection, where, args, sort)
            }
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<T>(limit.coerceAtMost(PAGE_CAPACITY))
        cursor.use { c -> while (c.moveToNext()) read(c)?.let(out::add) }
        return out
    }

    private const val PAGE_CAPACITY = 512

    private fun readItem(c: Cursor): GalleryItem? {
        val id = c.getLong(c.getColumnIndex(MediaStore.Files.FileColumns._ID).takeIf { it >= 0 } ?: return null)
        val mime = c.getStringOrEmpty(c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE))
        val kind = GalleryItem.kindOf(mime, c.getIntOr(c.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE), 0))
        val taken = c.getLongOr(c.getColumnIndex(dateColumn), 0L)
        val added = c.getLongOr(c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED), 0L) * 1000L
        val fav = c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
        return GalleryItem(
            id = id,
            mime = mime.ifEmpty { if (kind == GalleryKind.VIDEO) "video/mp4" else "image/jpeg" },
            kind = kind,
            name = c.getStringOrEmpty(c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)),
            width = c.getIntOr(c.getColumnIndex(MediaStore.MediaColumns.WIDTH), 0),
            height = c.getIntOr(c.getColumnIndex(MediaStore.MediaColumns.HEIGHT), 0),
            orientation = c.getIntOr(c.getColumnIndex(ORIENTATION), 0).mod(360),
            size = c.getLongOr(c.getColumnIndex(MediaStore.MediaColumns.SIZE), 0L),
            durationMs = if (kind == GalleryKind.VIDEO) c.getLongOr(c.getColumnIndex(DURATION), 0L) else 0L,
            dateMs = if (taken > 0) taken else added,
            bucketId = c.getStringOrEmpty(c.getColumnIndex(BUCKET_ID)),
            bucketName = c.getStringOrEmpty(c.getColumnIndex(BUCKET_NAME)),
            favorite = fav >= 0 && c.getIntOr(fav, 0) == 1,
            modifiedSec = c.getLongOr(c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED), 0L),
        )
    }

    private fun Cursor.getStringOrEmpty(index: Int): String =
        if (index < 0 || isNull(index)) "" else getString(index).orEmpty()

    private fun Cursor.getIntOr(index: Int, fallback: Int): Int =
        if (index < 0 || isNull(index)) fallback else getInt(index)

    private fun Cursor.getLongOr(index: Int, fallback: Long): Long =
        if (index < 0 || isNull(index)) fallback else getLong(index)
}

/**
 * Counts rows into albums during [GalleryStore.albums]'s single pass. Kept
 * apart from the cursor so the grouping rules are plain code a test can drive.
 * Rows arrive newest first, so the first row seen for an album is its cover.
 */
class AlbumTally(
    /** The cover's URI, made only for the rows that become covers rather than for every row. */
    private val coverUri: (Long, GalleryKind) -> Uri? = { _, _ -> null },
) {

    data class Row(
        val id: Long,
        val kind: GalleryKind,
        val bucketId: String,
        val bucketName: String,
        /** Folder and file name together, lower or upper case as stored. */
        val path: String,
        val favorite: Boolean,
        val sent: Boolean,
    )

    private class Count(var count: Int = 0, var coverId: Long = -1, var coverVideo: Boolean = false, var name: String = "") {
        var cover: Uri? = null
        var path: String = ""
    }

    private val virtual = LinkedHashMap<AlbumKind, Count>()
    private val buckets = LinkedHashMap<String, Count>()

    fun add(row: Row) {
        fun bump(c: Count) {
            if (c.count == 0) {
                c.coverId = row.id
                c.coverVideo = row.kind == GalleryKind.VIDEO
            }
            c.count++
        }
        bump(virtual.getOrPut(AlbumKind.RECENTS) { Count() })
        if (row.favorite) bump(virtual.getOrPut(AlbumKind.FAVORITES) { Count() })
        if (row.kind == GalleryKind.VIDEO) bump(virtual.getOrPut(AlbumKind.VIDEOS) { Count() })
        if (row.kind == GalleryKind.GIF) bump(virtual.getOrPut(AlbumKind.GIFS) { Count() })
        if (isScreenshot(row.path)) bump(virtual.getOrPut(AlbumKind.SCREENSHOTS) { Count() })
        if (isCamera(row.path)) bump(virtual.getOrPut(AlbumKind.CAMERA) { Count() })
        if (row.sent) bump(virtual.getOrPut(AlbumKind.SENT) { Count() })
        if (row.bucketId.isNotEmpty()) {
            val c = buckets.getOrPut(row.bucketId) { Count(name = row.bucketName) }
        buckets[row.bucketId]?.let { bucket ->
            if (bucket.path.isEmpty()) {
                // RELATIVE_PATH ends in a slash; before Android 10 the column is the whole file path.
                val folder = row.path.substringBeforeLast('/').trimEnd('/')
                bucket.path = if (folder.startsWith('/')) {
                    folder.substringBeforeLast('/').removePrefix("/storage/emulated/0/")
                } else {
                    folder
                }
            }
        }
            if (c.name.isEmpty()) c.name = row.bucketName
            bump(c)
        }
    }

    /**
     * Recents always (an empty library still has a place to land), the other
     * collections when they hold something, in a fixed order, then the folders
     * biggest first. Videos is left out when they are not listed at all.
     */
    fun albums(videos: Boolean): List<GalleryAlbum> {
        (virtual.values + buckets.values).forEach { c ->
            if (c.cover == null && c.coverId >= 0) c.cover = coverUri(c.coverId, if (c.coverVideo) GalleryKind.VIDEO else GalleryKind.IMAGE)
        }
        val order = listOf(
            AlbumKind.RECENTS, AlbumKind.SENT, AlbumKind.FAVORITES, AlbumKind.CAMERA,
            AlbumKind.SCREENSHOTS, AlbumKind.VIDEOS, AlbumKind.GIFS,
        )
        val own = order.mapNotNull { kind ->
            val c = virtual[kind]
            when {
                kind == AlbumKind.RECENTS -> GalleryAlbum.of(kind, c?.count ?: 0, c?.cover, c?.coverVideo == true)
                kind == AlbumKind.VIDEOS && !videos -> null
                c == null || c.count == 0 -> null
                else -> GalleryAlbum.of(kind, c.count, c.cover, c.coverVideo)
            }
        }
        val folders = buckets.entries
            .sortedWith(compareByDescending<Map.Entry<String, Count>> { it.value.count }.thenBy { it.value.name.lowercase() })
            .map { (id, c) -> GalleryAlbum(AlbumKind.BUCKET, id, c.name, c.count, c.cover, c.coverVideo, c.path) }
        return own + folders
    }

    companion object {
        /** The same three spellings [com.wasimaster.wmkeyboard.core.reply.ReplyShots] looks for. */
        fun isScreenshot(path: String): Boolean {
            val p = path.lowercase()
            return "screenshot" in p || "screen_shot" in p || "screen-shot" in p
        }

        fun isCamera(path: String): Boolean = "dcim/camera" in path.lowercase()
    }
}

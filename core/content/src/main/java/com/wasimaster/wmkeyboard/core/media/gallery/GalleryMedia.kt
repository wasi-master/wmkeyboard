package com.wasimaster.wmkeyboard.core.media.gallery

import android.net.Uri

/**
 * The gallery tool's view of the phone's photos and videos (#708).
 *
 * Read through MediaStore with the photos-and-videos permission, in the builds
 * that may hold it. Nothing here copies or uploads anything: the library is
 * only ever read, and only what the user sends leaves the keyboard, as a copy
 * made by [MediaPrep].
 */
enum class GalleryKind { IMAGE, GIF, VIDEO }

/** One photo or video in the library. */
data class GalleryItem(
    /** MediaStore's row id: stable for as long as the file exists. */
    val id: Long,
    val mime: String,
    val kind: GalleryKind,
    /** File name as the library keeps it; blank on stores that hide it. */
    val name: String,
    /** Pixel size before any rotation; 0 when the store has not measured it. */
    val width: Int,
    val height: Int,
    /** Clockwise degrees MediaStore says the picture is turned by: 0, 90, 180 or 270. */
    val orientation: Int,
    /** Bytes on disk; 0 when unknown. */
    val size: Long,
    /** Video length; 0 for pictures. */
    val durationMs: Long,
    /** When the photo was taken, or failing that when it was added, in epoch milliseconds. */
    val dateMs: Long,
    val bucketId: String,
    val bucketName: String,
    val favorite: Boolean,
    /**
     * Where the bytes are when this did not come from the library: a photo
     * picker or document picker result, read through that picker's grant.
     * Such an item has a negative [id].
     */
    val sourceUri: Uri? = null,
    /** When the file last changed, in epoch seconds; 0 when unknown. A new value means new pixels. */
    val modifiedSec: Long = 0,
) {
    /** The content URI to read: [sourceUri], or the library row's own under its type's table. */
    val uri: Uri by lazy(LazyThreadSafetyMode.NONE) { sourceUri ?: GalleryStore.uriOf(id, kind) }

    /** Picked through a system picker rather than read from the library. */
    val isPicked: Boolean get() = id < 0

    /** The size the picture is seen at, with [orientation] applied. */
    val shownWidth: Int get() = if (orientation % 180 == 0) width else height
    val shownHeight: Int get() = if (orientation % 180 == 0) height else width

    /** Width over height as shown, 1 when unknown. */
    val aspect: Float
        get() = if (shownWidth > 0 && shownHeight > 0) shownWidth.toFloat() / shownHeight else 1f

    val isVideo: Boolean get() = kind == GalleryKind.VIDEO

    companion object {
        fun kindOf(mime: String, mediaType: Int): GalleryKind = when {
            mime.startsWith("video/") || mediaType == MEDIA_TYPE_VIDEO -> GalleryKind.VIDEO
            mime == "image/gif" -> GalleryKind.GIF
            else -> GalleryKind.IMAGE
        }

        /** MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, kept here so the rule is testable off-device. */
        const val MEDIA_TYPE_VIDEO = 3
    }
}

/** What an album is: one of the tool's own collections, or a folder on the phone. */
enum class AlbumKind {
    /** Everything, newest first. */
    RECENTS,

    /** What the gallery app has starred (Android 11 and up). */
    FAVORITES,
    VIDEOS,
    GIFS,
    SCREENSHOTS,
    CAMERA,

    /** What this keyboard has sent before, newest send first. */
    SENT,

    /** A folder on the phone, by MediaStore bucket. */
    BUCKET,
}

/** An album in the picker's list. */
data class GalleryAlbum(
    val kind: AlbumKind,
    /** The bucket id for [AlbumKind.BUCKET]; the kind's name otherwise. */
    val id: String,
    /** The folder's own name; null for the tool's collections, which the keyboard names. */
    val name: String?,
    val count: Int,
    /** The newest item's picture, for the album's tile. */
    val cover: Uri?,
    val coverIsVideo: Boolean = false,
    /** The folder's path from the top of storage, for a bucket; empty otherwise. */
    val path: String = "",
) {
    companion object {
        fun of(kind: AlbumKind, count: Int = 0, cover: Uri? = null, coverIsVideo: Boolean = false) =
            GalleryAlbum(kind, kind.name, null, count, cover, coverIsVideo)

        val Recents = of(AlbumKind.RECENTS)
    }
}

/** The chips over the grid: everything, or one kind of thing. */
enum class GalleryFilter {
    ALL, PHOTOS, VIDEOS, GIFS;

    fun accepts(kind: GalleryKind): Boolean = when (this) {
        ALL -> true
        PHOTOS -> kind == GalleryKind.IMAGE
        VIDEOS -> kind == GalleryKind.VIDEO
        GIFS -> kind == GalleryKind.GIF
    }
}

/**
 * How big a sent picture is: its longest edge in pixels, or the file as it is.
 * Videos are never re-encoded, so for them only [ORIGINAL] versus the rest
 * matters: the rest still clears their metadata.
 */
enum class GalleryQuality(val maxEdge: Int, val jpegQuality: Int) {
    /** The photo's own bytes, metadata cleared as the privacy settings say. */
    ORIGINAL(0, 95),
    HD(2560, 90),
    STANDARD(1280, 85),
    SMALL(800, 80),
    ;

    companion object {
        fun of(name: String?): GalleryQuality? = entries.firstOrNull { it.name == name }
    }
}

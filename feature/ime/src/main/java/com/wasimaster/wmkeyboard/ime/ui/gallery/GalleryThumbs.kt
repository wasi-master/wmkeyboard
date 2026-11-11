package com.wasimaster.wmkeyboard.ime.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.Options
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryItem
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryKind
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.ime.gallery.immich.ImmichLibrary
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * A grid cell's picture: the system's own thumbnail of a library row.
 *
 * Asked of MediaStore rather than decoded from the file, because MediaStore
 * keeps a thumbnail cache of its own and a video has no picture to decode at
 * all. [px] is the side the cell is drawn at, so a five-column grid asks for
 * less than a three-column one.
 */
data class MediaThumb(
    val uri: Uri,
    val video: Boolean,
    val px: Int,
    /** The file's change time, so a photo written over (from the editor) is fetched afresh. */
    val version: Long = 0,
) {
    companion object {
        fun of(item: GalleryItem, px: Int) = MediaThumb(item.uri, item.kind == GalleryKind.VIDEO, px, item.modifiedSec)
    }
}

/** Fetches a [MediaThumb] through [ContentResolver.loadThumbnail], with a decode or a frame as the fallback. */
private class MediaThumbFetcher(
    private val context: Context,
    private val thumb: MediaThumb,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bitmap = load() ?: return null
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun load(): Bitmap? {
        val side = thumb.px.coerceIn(MIN_PX, MAX_PX)
        // An Immich picture (#1205): the server's own thumbnail, its larger one for a big cell.
        if (ImmichLibrary.assetOf(thumb.uri) != null) {
            val bytes = runCatching { ImmichLibrary.picture(thumb.uri, preview = side > IMMICH_SMALL_PX) }.getOrNull()
                ?: return null
            return decodeBytes(bytes, side)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { context.contentResolver.loadThumbnail(thumb.uri, Size(side, side), null) }
                .getOrNull()?.let { return it }
        }
        return if (thumb.video) frame(side) else MediaPrep.decode(context, thumb.uri, side)
    }

    /** A video's first frame, scaled, where the thumbnail call is missing or failed. */
    private fun frame(side: Int): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, thumb.uri)
            val f = r.getFrameAtTime(0) ?: return@runCatching null
            val (w, h) = MediaPrep.targetSize(f.width, f.height, side)
            if (w == f.width) f else Bitmap.createScaledBitmap(f, w, h, true).also { f.recycle() }
        } finally {
            r.release()
        }
    }.getOrNull()

    class Factory(private val context: Context) : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbFetcher(context, data)
    }

    companion object {
        const val MIN_PX = 96
        const val MAX_PX = 512

        /** Immich's `thumbnail` size is about 250 px; past that its `preview` is asked for. */
        const val IMMICH_SMALL_PX = 320
    }
}

/** [bytes] decoded no bigger than [side] on its long edge. */
private fun decodeBytes(bytes: ByteArray, side: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val opts = BitmapFactory.Options().apply { inSampleSize = MediaPrep.sampleSize(bounds.outWidth, bounds.outHeight, side) }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}.getOrNull()

/**
 * An Immich picture at full size, for the viewer and the peek (#1205): the
 * server's preview, about 1440 px, which is what its own apps show before the
 * original is asked for. Any other address goes on to Coil's own fetchers.
 */
private class ImmichPictureFetcher(private val uri: android.net.Uri, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bytes = withContext(Dispatchers.IO) { ImmichLibrary.picture(uri, preview = true) } ?: return null
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != GalleryItem.REMOTE_SCHEME) return null
            return ImmichPictureFetcher(android.net.Uri.parse(data.toString()), options)
        }
    }
}

/** Keys a thumbnail by row and size, so the memory cache can hold it. */
private class MediaThumbKeyer : Keyer<MediaThumb> {
    override fun key(data: MediaThumb, options: Options): String = "gthumb:${data.uri}:${data.px}:${data.version}"
}

@Volatile
private var galleryLoader: ImageLoader? = null

/**
 * The gallery's own image loader: the system thumbnails above, and full
 * pictures for the viewer (GIFs animated) through Coil's own content-URI
 * fetcher. Apart from the media loader because MediaStore caches its own
 * thumbnails on disk already. Its one way onto the network is an Immich
 * picture (#1205), fetched through [ImmichLibrary], whose client is gated and
 * logged; Coil's own network fetcher is not part of this loader.
 */
fun galleryImageLoader(context: Context): ImageLoader =
    galleryLoader ?: synchronized(MediaThumbKeyer::class) {
        galleryLoader ?: ImageLoader.Builder(context.applicationContext)
            .components {
                add(MediaThumbFetcher.Factory(context.applicationContext), MediaThumb::class)
                add(MediaThumbKeyer(), MediaThumb::class)
                add(ImmichPictureFetcher.Factory(), coil3.Uri::class)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context.applicationContext, MEMORY_SHARE).build() }
            .build()
            .also { galleryLoader = it }
    }

/** Gives the loader's memory back once the panel is gone; see `trimMediaImageMemory`. */
fun trimGalleryImageMemory() {
    galleryLoader?.memoryCache?.clear()
}

@Composable
fun rememberGalleryImageLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) { galleryImageLoader(context) }
}

/** A share of the heap: a few screens of thumbnails, the same budget as the media panels'. */
private const val MEMORY_SHARE = 0.10

/** A sticker file the editor places, decoded small enough for a tray cell or a layer. */
fun decodeStickerFile(path: String, maxSide: Int = 512): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = MediaPrep.sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
    }
    BitmapFactory.decodeFile(path, opts)
}.getOrNull()

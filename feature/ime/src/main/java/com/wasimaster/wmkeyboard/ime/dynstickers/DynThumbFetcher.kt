package com.wasimaster.wmkeyboard.ime.dynstickers

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import okio.Buffer

/**
 * The `wmdyn:` addresses dynamic sticker cells carry, and who draws them.
 *
 * The address names an entry the [DynStickerController] keeps (a template and
 * its fill); the controller registers itself as [source] when the keyboard
 * makes it. Nothing is fetched from anywhere: the picture is drawn on the
 * device the moment a cell asks for it.
 */
internal object DynThumbs {
    const val SCHEME = "wmdyn"

    @Volatile
    var source: DynStickerController? = null

    fun uri(id: String, animated: Boolean): String = "$SCHEME://t/$id" + if (animated) "?a=1" else ""
}

/** Draws a `wmdyn:` cell for Coil: a still, or with `a=1` a small animated WebP. */
internal class DynThumbFetcher(
    private val id: String,
    private val animated: Boolean,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val controller = DynThumbs.source ?: return null
        val px = maxOf(options.size.width.pxOrElse { DEFAULT_PX }, options.size.height.pxOrElse { DEFAULT_PX })
        if (animated) {
            controller.animatedThumb(id, px)?.let { bytes ->
                return SourceFetchResult(
                    source = ImageSource(Buffer().write(bytes), options.fileSystem),
                    mimeType = "image/webp",
                    dataSource = DataSource.DISK,
                )
            }
        }
        val bitmap = controller.thumb(id, px) ?: return null
        return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.MEMORY)
    }

    class Factory : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != DynThumbs.SCHEME) return null
            val id = data.path?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
            return DynThumbFetcher(id, animated = data.query?.contains("a=1") == true, options)
        }
    }

    private companion object {
        const val DEFAULT_PX = 256
    }
}

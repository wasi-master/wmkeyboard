package com.wasimaster.wmkeyboard.core.media.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.wasimaster.wmkeyboard.core.media.MediaScrub
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Turns a photo or video the user picked into the copy that is sent (#708).
 *
 * The app on the other end of the keyboard never sees the library: it gets a
 * file in the keyboard's own cache, under a FileProvider URI, made here. That
 * copy is where the privacy settings act. A picture that is resized is
 * re-encoded, which leaves every bit of metadata behind; one sent at its
 * original size has its location and, when asked, its camera tags cleared; a
 * video is remuxed into a fresh container, which carries the streams and the
 * rotation and nothing else.
 *
 * Every function blocks and belongs on a background thread.
 */
object MediaPrep {

    /** What the copy should be. */
    data class Options(
        val quality: GalleryQuality = GalleryQuality.HD,
        /** Clears the GPS tags from a photo sent at its own size, and the location from a video. */
        val stripLocation: Boolean = true,
        /** Clears the camera, owner, serial and date tags too. */
        val stripAll: Boolean = true,
        /** Names the copy `IMG_` and a random string rather than the photo's own file name. */
        val randomName: Boolean = true,
    )

    /** A copy ready to send. */
    data class Prepared(val file: File, val mime: String, val width: Int, val height: Int)

    /**
     * Longest edge a picture is decoded at when it is edited or sent at its
     * own size but has to be re-encoded: 12 megapixels is more than any chat
     * app keeps, and twice that is how an input method gets killed for memory.
     */
    const val MAX_DECODE_EDGE = 4096

    private val random = SecureRandom()

    fun outputDir(context: Context): File = File(File(context.cacheDir, "media"), "gallery").apply { mkdirs() }

    /** Removes copies older than [maxAgeMs]: the app they went to has long read them by then. */
    fun sweep(context: Context, maxAgeMs: Long = SWEEP_AGE_MS) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        outputDir(context).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    const val SWEEP_AGE_MS = 24L * 60 * 60 * 1000

    /**
     * Deletes every copy the keyboard made for sending once it is a day old:
     * GIFs, stickers and pictures under `cache/media`, camera shots and page
     * scans. Each was written for one app to read once; keeping it longer
     * only keeps a copy of what the user sent on the phone.
     */
    fun sweepSent(context: Context, maxAgeMs: Long = SWEEP_AGE_MS) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        listOf(File(context.cacheDir, "media"), File(context.filesDir, "camera"), File(context.filesDir, "docscan"))
            .forEach { dir -> dir.walkBottomUp().filter { it.isFile && it.lastModified() < cutoff }.forEach { it.delete() } }
    }

    // ---- Pictures ------------------------------------------------------

    /**
     * The picture as it will be sent. [edit], when given, draws the user's
     * edits onto the decoded picture and returns the result (the editor's
     * renderer); an edited picture is always re-encoded, at the quality's size.
     * [from] is where the picture's pixels are read: the item itself, or the
     * editor's upscaled copy of it.
     */
    fun prepareImage(
        context: Context,
        item: GalleryItem,
        options: Options,
        from: Uri = item.uri,
        edit: ((Bitmap) -> Bitmap)? = null,
    ): Prepared? = runCatching {
        // A GIF goes as the GIF it is: re-encoding would keep one frame. Its
        // comments and XMP block come out with the camera details (#1179).
        if (item.kind == GalleryKind.GIF && edit == null) {
            val copy = copyAsIs(context, item, "image/gif", "gif", options) ?: return@runCatching null
            if (options.stripAll) MediaScrub.image(context, copy.file, copy.mime, reencode = false)
            return@runCatching copy
        }
        val keepBytes = options.quality == GalleryQuality.ORIGINAL && edit == null && canRewriteExif(item.mime)
        if (keepBytes) {
            val ext = extensionFor(item.mime)
            val copy = copyAsIs(context, item, item.mime, ext, options) ?: return@runCatching null
            // Every metadata block out, byte for byte (#1179): IPTC, comments
            // and a motion photo's trailer too, which ExifInterface leaves. The
            // tag-by-tag clear stays for the location alone, and as the
            // fallback for a file the block walk will not take.
            val scrubbed = options.stripAll && MediaScrub.image(context, copy.file, copy.mime, reencode = false) != null
            if (!scrubbed && (options.stripLocation || options.stripAll)) clearExif(copy.file, options.stripAll)
            return@runCatching copy
        }
        val maxEdge = if (options.quality.maxEdge > 0) options.quality.maxEdge else MAX_DECODE_EDGE
        val decoded = decode(context, from, maxEdge) ?: return@runCatching null
        val drawn = edit?.invoke(decoded) ?: decoded
        val scaled = scaleDown(drawn, maxEdge)
        val alpha = scaled.hasAlpha() && item.mime == "image/png"
        val mime = if (alpha) "image/png" else "image/jpeg"
        val file = File(outputDir(context), fileName(item, if (alpha) "png" else "jpg", options))
        file.outputStream().use { out ->
            @Suppress("DEPRECATION")
            scaled.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, options.quality.jpegQuality, out)
        }
        Prepared(file, mime, scaled.width, scaled.height).also {
            if (scaled !== drawn) scaled.recycle()
            if (drawn !== decoded) drawn.recycle()
            decoded.recycle()
        }
    }.getOrNull()

    /**
     * An edited picture to keep in the gallery rather than send: in the
     * photo's own format where Android can write it (JPEG otherwise), at the
     * editor's largest size, with the photo's own tags carried over so it
     * keeps its date and camera. [draw] is the editor's renderer; [from] as
     * for [prepareImage].
     */
    fun renderToKeep(
        context: Context,
        item: GalleryItem,
        from: Uri = item.uri,
        draw: (Bitmap) -> Bitmap,
    ): Prepared? = runCatching {
        val decoded = decode(context, from, MAX_DECODE_EDGE) ?: return@runCatching null
        val drawn = draw(decoded)
        val mime = if (canRewriteExif(item.mime)) item.mime else "image/jpeg"
        val file = File(outputDir(context), "KEEP_${randomToken()}.${extensionFor(mime)}")
        file.outputStream().use { out -> drawn.compress(compressFormat(mime), KEEP_QUALITY, out) }
        copyTags(context, item.uri, file)
        Prepared(file, mime, drawn.width, drawn.height).also {
            if (drawn !== decoded) drawn.recycle()
            decoded.recycle()
        }
    }.getOrNull()

    private const val KEEP_QUALITY = 95

    @Suppress("DEPRECATION")
    private fun compressFormat(mime: String): Bitmap.CompressFormat = when (mime) {
        "image/png" -> Bitmap.CompressFormat.PNG
        "image/webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        else -> Bitmap.CompressFormat.JPEG
    }

    /**
     * The tags a kept copy takes from its photo: when, where and with what.
     * Not the XMP or the maker note, which point into bytes the new file no
     * longer has (a motion photo's video, a depth map).
     */
    // Lazy: LOCATION_TAGS is declared further down this object, so an eager
    // initializer here would read it before it is set.
    private val KEEP_TAGS: List<String> by lazy { LOCATION_TAGS + listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
    ) }

    /**
     * Copies [KEEP_TAGS] from the photo at [from] onto [to], marked upright,
     * since the pixels were turned when decoded. Without the media-location
     * permission Android hands apps the photo with its location left out, so
     * the place stays behind with the original.
     */
    private fun copyTags(context: Context, from: Uri, to: File) {
        runCatching {
            val src = context.contentResolver.openInputStream(from)?.use { ExifInterface(it) } ?: return
            val dst = ExifInterface(to)
            KEEP_TAGS.forEach { tag -> src.getAttribute(tag)?.let { dst.setAttribute(tag, it) } }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.saveAttributes()
        }
    }

    /**
     * Decodes [uri] upright, no larger than [maxEdge] along its longest side
     * (a power-of-two sample first, so the full-size picture is never in
     * memory, then an exact scale).
     */
    fun decode(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder turns the picture by its EXIF orientation itself,
            // and reads HEIF, which BitmapFactory does not everywhere.
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return@runCatching ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (w, h) = targetSize(info.size.width, info.size.height, maxEdge)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inMutable = true
        }
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching null
        val degrees = context.contentResolver.openInputStream(uri)?.use { stream ->
            rotationOf(ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        } ?: Orientation()
        upright(raw, degrees)
    }.getOrNull()

    /** A turn and a mirror, as an EXIF orientation describes them. */
    data class Orientation(val degrees: Int = 0, val mirror: Boolean = false)

    /** The turn and mirror behind each of EXIF's eight orientation values. */
    fun rotationOf(exif: Int): Orientation = when (exif) {
        ExifInterface.ORIENTATION_ROTATE_90 -> Orientation(90)
        ExifInterface.ORIENTATION_ROTATE_180 -> Orientation(180)
        ExifInterface.ORIENTATION_ROTATE_270 -> Orientation(270)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Orientation(0, mirror = true)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Orientation(180, mirror = true)
        ExifInterface.ORIENTATION_TRANSPOSE -> Orientation(90, mirror = true)
        ExifInterface.ORIENTATION_TRANSVERSE -> Orientation(270, mirror = true)
        else -> Orientation()
    }

    private fun upright(bitmap: Bitmap, o: Orientation): Bitmap {
        if (o.degrees == 0 && !o.mirror) return bitmap
        val m = Matrix().apply {
            if (o.mirror) postScale(-1f, 1f)
            postRotate(o.degrees.toFloat())
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val (w, h) = targetSize(bitmap.width, bitmap.height, maxEdge)
        if (w == bitmap.width && h == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /**
     * [width] by [height] shrunk so the longest side is at most [maxEdge],
     * keeping the shape; unchanged when it already fits or [maxEdge] is 0.
     */
    fun targetSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (maxEdge <= 0 || longest <= maxEdge || longest == 0) return width to height
        val ratio = maxEdge.toDouble() / longest
        return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
    }

    /** The largest power-of-two decode divisor that still leaves the picture at least [maxEdge] long. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (maxEdge <= 0) return 1
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxEdge) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * About how many bytes [item] will be once prepared at [quality], for the
     * total the send bar shows. A guess, from the pixel count: about 1.6 bits
     * a pixel at HD-ish JPEG quality, which is what phone photos land at.
     */
    fun estimateBytes(item: GalleryItem, quality: GalleryQuality, edited: Boolean = false): Long {
        if (item.kind != GalleryKind.IMAGE && !edited) return item.size
        if (quality == GalleryQuality.ORIGINAL && !edited) return item.size
        val maxEdge = if (quality.maxEdge > 0) quality.maxEdge else MAX_DECODE_EDGE
        if (item.width <= 0 || item.height <= 0) return item.size
        val (w, h) = targetSize(item.width, item.height, maxEdge)
        val bitsPerPixel = when (quality) {
            GalleryQuality.ORIGINAL -> 2.4
            GalleryQuality.HD -> 1.8
            GalleryQuality.STANDARD -> 1.5
            GalleryQuality.SMALL -> 1.3
        }
        val guess = (w.toLong() * h * bitsPerPixel / 8).toLong()
        // A re-encode does not come out bigger than a file that was already small.
        return if (item.size > 0) minOf(guess, item.size) else guess
    }

    // ---- Metadata ------------------------------------------------------

    /** Whether [ExifInterface] can write this type back; HEIF it can only read. */
    fun canRewriteExif(mime: String): Boolean = mime == "image/jpeg" || mime == "image/png" || mime == "image/webp"

    /** Location tags: every GPS field ExifInterface knows. */
    val LOCATION_TAGS: List<String> = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR, ExifInterface.TAG_GPS_VERSION_ID,
    )

    /**
     * The tags that say who took a photo, with what, and when: everything
     * that identifies a person or a device beyond the picture itself. The
     * orientation and the colour tags stay, or the picture would arrive
     * sideways or in the wrong colours.
     */
    val IDENTIFYING_TAGS: List<String> = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_MAKER_NOTE, ExifInterface.TAG_IMAGE_UNIQUE_ID,
        ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_XMP,
    )

    /** The tags [clearExif] removes. */
    fun tagsToClear(all: Boolean): List<String> = if (all) LOCATION_TAGS + IDENTIFYING_TAGS else LOCATION_TAGS

    /** Clears [tagsToClear] from [file] in place. True when the file was rewritten. */
    fun clearExif(file: File, all: Boolean): Boolean = runCatching {
        val exif = ExifInterface(file)
        val tags = tagsToClear(all).filter { exif.getAttribute(it) != null }
        if (tags.isEmpty()) return@runCatching false
        tags.forEach { exif.setAttribute(it, null) }
        exif.saveAttributes()
        true
    }.getOrDefault(false)

    // ---- Videos and as-is copies ---------------------------------------

    /**
     * The video as it will be sent. With either privacy setting on, its
     * streams go into a new MP4 or WebM, which drops the location atom and
     * every other tag the camera wrote; the turn is carried over by hand. A
     * container the muxer cannot write, or one it fails on halfway, is sent
     * as the original bytes instead.
     */
    fun prepareVideo(context: Context, item: GalleryItem, options: Options): Prepared? {
        val format = muxerFormatFor(item.mime)
        if ((options.stripLocation || options.stripAll) && format != null) {
            // What the muxer writes is MP4 or WebM, whatever it was read from.
            val mime = if (format == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM) "video/webm" else "video/mp4"
            val out = File(outputDir(context), fileName(item, extensionFor(mime), options))
            if (runCatching { remux(context, item.uri, out, format) }.getOrDefault(false)) {
                return Prepared(out, mime, item.shownWidth, item.shownHeight)
            }
            out.delete()
        }
        return copyAsIs(context, item, item.mime, extensionFor(item.mime), options)
    }

    /**
     * A copy of the video at [uri] in a fresh container, with its streams and
     * turn and none of its tags (#1179): the clipboard's paste of a video
     * kept by reference. Null when the muxer cannot write the container or
     * fails on it; the caller decides what that means.
     */
    fun remuxClean(context: Context, uri: Uri, mime: String, outDir: File): Prepared? {
        val format = muxerFormatFor(mime) ?: return null
        val outMime = if (format == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM) "video/webm" else "video/mp4"
        val out = File(outDir, "VID_${randomToken()}.${extensionFor(outMime)}")
        if (runCatching { remux(context, uri, out, format) }.getOrDefault(false)) return Prepared(out, outMime, 0, 0)
        out.delete()
        return null
    }

    /** The muxer format for a container it can write, or null. */
    fun muxerFormatFor(mime: String): Int? = when (mime) {
        "video/mp4", "video/3gpp", "video/quicktime" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        "video/webm" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
        else -> null
    }

    @Suppress("detekt.NestedBlockDepth")
    private fun remux(context: Context, uri: Uri, out: File, format: Int): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { extractor.setDataSource(it.fileDescriptor) }
                ?: return false
            muxer = MediaMuxer(out.absolutePath, format)
            val map = HashMap<Int, Int>()
            var rotation = 0
            var bufferSize = MIN_REMUX_BUFFER
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime.startsWith("video/") && f.containsKey(KEY_ROTATION)) rotation = f.getInteger(KEY_ROTATION)
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = maxOf(bufferSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                map[i] = muxer.addTrack(f)
                extractor.selectTrack(i)
            }
            if (map.isEmpty()) return false
            if (rotation != 0) muxer.setOrientationHint(rotation)
            muxer.start()
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                map[track]?.let { muxer.writeSampleData(it, buffer, info) }
                extractor.advance()
            }
            muxer.stop()
            return out.length() > 0
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    // MediaFormat.KEY_ROTATION is API 23; the string is the same below it.
    private const val KEY_ROTATION = "rotation-degrees"
    private const val MIN_REMUX_BUFFER = 1 shl 20

    /** A byte-for-byte copy of [item] into the cache. */
    fun copyAsIs(context: Context, item: GalleryItem, mime: String, ext: String, options: Options): Prepared? = runCatching {
        val file = File(outputDir(context), fileName(item, ext, options))
        context.contentResolver.openInputStream(item.uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: return@runCatching null
        Prepared(file, mime, item.shownWidth, item.shownHeight)
    }.getOrNull()

    /** The copy's file name: random, or the photo's own (made safe and unique). */
    fun fileName(item: GalleryItem, ext: String, options: Options): String {
        val prefix = if (item.kind == GalleryKind.VIDEO) "VID" else "IMG"
        if (options.randomName || item.name.isBlank()) return "${prefix}_${randomToken()}.$ext"
        val base = item.name.substringBeforeLast('.').replace(UNSAFE, "_").take(MAX_NAME).ifBlank { prefix }
        // The token keeps two sends of one photo, edited differently, apart.
        return "${base}_${randomToken().take(4)}.$ext"
    }

    private val UNSAFE = Regex("[^A-Za-z0-9._ -]")
    private const val MAX_NAME = 80

    private fun randomToken(): String {
        val bytes = ByteArray(5)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun extensionFor(mime: String): String = when (mime) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        "video/mp4" -> "mp4"
        "video/3gpp" -> "3gp"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        else -> mime.substringAfter('/', "bin").substringBefore(';').take(5).ifBlank { "bin" }
    }
}

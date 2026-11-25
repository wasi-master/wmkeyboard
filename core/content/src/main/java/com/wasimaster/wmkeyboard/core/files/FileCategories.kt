package com.wasimaster.wmkeyboard.core.files

import android.content.Context
import android.provider.MediaStore
import java.io.File

/** A view across the whole phone by kind, as the Places tiles offer them. */
enum class FileCategory(val kinds: Set<FileKind>) {
    IMAGES(setOf(FileKind.IMAGE)),
    VIDEOS(setOf(FileKind.VIDEO)),
    AUDIO(setOf(FileKind.AUDIO)),
    DOCUMENTS(setOf(FileKind.PDF, FileKind.DOC, FileKind.SHEET, FileKind.SLIDES, FileKind.TEXT, FileKind.EBOOK)),
    APKS(setOf(FileKind.APK)),
    ARCHIVES(setOf(FileKind.ARCHIVE)),

    /** Anything over [LARGE_BYTES], biggest first: what to clear when the phone is full. */
    LARGE(emptySet()),
}

/**
 * The category views, from MediaStore's index of shared storage, so a list of
 * every picture on the phone needs no walk. Needs All files access to read
 * the paths, and a background thread. Rows whose file is gone, or that sit in
 * a hidden folder, are left out.
 */
object FileCategories {

    const val LARGE_BYTES = 50L * 1024 * 1024

    /** The most rows one category lists; past this a search is the better way in. */
    const val LIMIT = 3000

    private val DOC_EXTENSIONS = listOf(
        "pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "txt", "md", "epub", "mobi", "djvu",
    )
    private val ARCHIVE_EXTENSIONS = listOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst")
    private val APK_EXTENSIONS = listOf("apk", "apks", "xapk", "apkm")

    @Suppress("DEPRECATION")
    private val DATA = MediaStore.Files.FileColumns.DATA
    private const val SIZE = MediaStore.Files.FileColumns.SIZE
    private const val MODIFIED = MediaStore.Files.FileColumns.DATE_MODIFIED
    private const val TYPE = MediaStore.Files.FileColumns.MEDIA_TYPE

    private fun selection(category: FileCategory): Pair<String, Array<String>> {
        fun extensions(list: List<String>): Pair<String, Array<String>> =
            list.joinToString(" OR ") { "$DATA LIKE ?" }.let { "($it)" } to list.map { "%.$it" }.toTypedArray()
        return when (category) {
            FileCategory.IMAGES -> "$TYPE = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            FileCategory.VIDEOS -> "$TYPE = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            FileCategory.AUDIO -> "$TYPE = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString())
            FileCategory.DOCUMENTS -> extensions(DOC_EXTENSIONS)
            FileCategory.APKS -> extensions(APK_EXTENSIONS)
            FileCategory.ARCHIVES -> extensions(ARCHIVE_EXTENSIONS)
            FileCategory.LARGE -> "$SIZE > ?" to arrayOf(LARGE_BYTES.toString())
        }
    }

    private fun order(sort: FileSort, descending: Boolean): String {
        val direction = if (descending) "DESC" else "ASC"
        return when (sort) {
            FileSort.NAME, FileSort.TYPE -> "${MediaStore.Files.FileColumns.DISPLAY_NAME} COLLATE NOCASE $direction"
            FileSort.DATE -> "$MODIFIED $direction"
            FileSort.SIZE -> "$SIZE $direction"
        }
    }

    /** How many files the category holds, by the index's count. */
    fun count(context: Context, category: FileCategory): Int = runCatching {
        val (where, args) = selection(category)
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns._ID),
            "$where AND $DATA NOT LIKE ?",
            args + "%/.%",
            null,
        )?.use { it.count } ?: 0
    }.getOrDefault(0)

    /**
     * The category's files in [sort] order (the large files always biggest
     * first), at most [LIMIT], narrowed to [mimes] for a tool's pick.
     */
    fun list(
        context: Context,
        category: FileCategory,
        sort: FileSort,
        descending: Boolean,
        showHidden: Boolean,
        mimes: List<String>,
    ): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        val (where, args) = selection(category)
        val order = if (category == FileCategory.LARGE) order(FileSort.SIZE, true) else order(sort, descending)
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(DATA, SIZE, MODIFIED),
                "$where AND $DATA IS NOT NULL",
                args,
                order,
            )?.use { c ->
                while (c.moveToNext() && out.size < LIMIT) {
                    val path = c.getString(0) ?: continue
                    if (!showHidden && path.contains("/.")) continue
                    val name = path.substringAfterLast('/')
                    val mime = FilePlaces.mimeOf(name).orEmpty()
                    if (!FileStore.mimeMatches(mime, mimes)) continue
                    // The index can trail a deletion by a while; a quick look keeps a ghost off the list.
                    val file = File(path)
                    if (!file.isFile) continue
                    out += FileEntry(path, name, false, c.getLong(1), c.getLong(2) * 1000, mime)
                }
            }
        }
        return out
    }
}

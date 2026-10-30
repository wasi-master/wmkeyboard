package com.wasimaster.wmkeyboard.core.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

/** One storage volume: the phone's own, or an SD card or a USB stick. */
data class FileVolume(val path: String, val label: String, val primary: Boolean)

/** The well-known folders the file manager offers a shortcut to. */
enum class FileShortcut(val directory: String) {
    DOWNLOADS(Environment.DIRECTORY_DOWNLOADS),
    DOCUMENTS(Environment.DIRECTORY_DOCUMENTS),
    PICTURES(Environment.DIRECTORY_PICTURES),
    CAMERA(Environment.DIRECTORY_DCIM),
    MUSIC(Environment.DIRECTORY_MUSIC),
    MOVIES(Environment.DIRECTORY_MOVIES),
}

/** The Android half of the file manager: the grant, the volumes, the shortcuts and the recent files. */
object FilePlaces {

    private const val RECENT_LIMIT = 100
    private const val RECENT_ROWS_MAX = 5000

    /** Whether the keyboard may browse shared storage: All files access, or the old pair before Android 11. */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    fun mimeOf(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    /** The mounted volumes, the phone's own first. */
    fun volumes(context: Context): List<FileVolume> {
        val app = context.applicationContext
        val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            app.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty().mapNotNull { volume ->
                val dir = volume.directory ?: return@mapNotNull null
                if (volume.state != Environment.MEDIA_MOUNTED) return@mapNotNull null
                FileVolume(dir.path, volume.getDescription(app), volume.isPrimary)
            }
        } else {
            // Each volume's app folder is `<volume>/Android/data/<package>/files`.
            @Suppress("DEPRECATION")
            val primary = Environment.getExternalStorageDirectory().path
            app.getExternalFilesDirs(null).filterNotNull().map { dir ->
                val root = dir.path.substringBefore("/Android/")
                FileVolume(root, File(root).name, root == primary)
            }
        }
        return found.sortedByDescending { it.primary }
    }

    /** Where [shortcut] is on the phone's own storage. */
    @Suppress("DEPRECATION")
    fun folderOf(shortcut: FileShortcut): File =
        Environment.getExternalStoragePublicDirectory(shortcut.directory)

    /**
     * The newest files on the device, newest first, from MediaStore's index of
     * everything on shared storage, which is why this needs no walk. Files
     * since deleted are dropped. Off the main thread; a device that refuses
     * the query has no recents rather than an error.
     */
    fun recent(context: Context, mimes: List<String>, showHidden: Boolean): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        @Suppress("DEPRECATION")
        val data = MediaStore.Files.FileColumns.DATA
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(data),
                "$data IS NOT NULL",
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                // The folders are rows too, and a narrow filter could otherwise read the whole table.
                while (c.moveToNext() && out.size < RECENT_LIMIT && c.position < RECENT_ROWS_MAX) {
                    val path = c.getString(0) ?: continue
                    val file = File(path)
                    if (!file.isFile) continue
                    if (!showHidden && path.contains("/.")) continue
                    val entry = FileStore.entryOf(file, ::mimeOf)
                    if (FileStore.mimeMatches(entry.mime, mimes)) out += entry
                }
            }
        }
        return out
    }

    /** Tells the media library what changed, so the gallery and other apps catch up. */
    fun scan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null, null)
        }
    }

    /** A pick's URI, as [LocalFilesProvider] serves it. */
    fun uriOf(context: Context, path: String): Uri = LocalFilesProvider.uriFor(context, File(path))
}

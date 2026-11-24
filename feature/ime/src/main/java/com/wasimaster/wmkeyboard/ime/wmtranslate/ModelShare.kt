package com.wasimaster.wmkeyboard.ime.wmtranslate

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The model files WM Translate may copy instead of downloading them again: only the folders the
 * keyboard's translation, speech and reading engines download into, never anything else in its
 * storage (clipboard history, notes and snippets live beside them). WM Translate checks each copy
 * against its own SHA-256 pin before using it, so a file here is only ever a faster download.
 */
object ModelShare {
    /** Folders under no-backup storage (`nb/`) and files storage (`f/`), as the engines name them. */
    private val ROOTS = listOf("nb/bergamot", "nb/nllb", "f/whisper", "f/sherpa", "f/mms-tts", "f/models", "f/ocr/tessdata")

    private fun base(context: Context, root: String): File? = when (root.substringBefore('/')) {
        "nb" -> context.noBackupFilesDir
        "f" -> context.filesDir
        else -> null
    }?.let { File(it, root.substringAfter('/')) }

    /** Every finished file under the shared folders, as `[{"path","name","size"}]`. */
    fun list(context: Context): String {
        val out = JSONArray()
        for (root in ROOTS) {
            val dir = base(context, root) ?: continue
            dir.walkTopDown().maxDepth(4).filter { it.isFile && usable(it) }.forEach { f ->
                out.put(JSONObject().put("path", root + "/" + f.relativeTo(dir).invariantSeparatorsPath).put("name", f.name).put("size", f.length()))
            }
        }
        return out.toString()
    }

    /** A half-written or hidden file is never offered. */
    private fun usable(f: File) = !f.name.startsWith(".") && !f.name.endsWith(".part") && !f.name.endsWith(".tmp") && f.length() > 0

    /** The file [path] names, when it lies inside a shared folder; null for anything else. */
    fun resolve(context: Context, path: String): File? {
        val root = ROOTS.firstOrNull { path.startsWith("$it/") } ?: return null
        val dir = base(context, root)?.canonicalFile ?: return null
        val file = File(dir, path.removePrefix("$root/")).canonicalFile
        if (!file.path.startsWith(dir.path + File.separator) || !file.isFile || !usable(file)) return null
        return file
    }
}

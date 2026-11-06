package com.wasimaster.wmkeyboard.core.voice.executorch

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Unpacks a model that comes as one zip (Vosk's, #1165) into its folder. The
 * archive is hash-checked whole before this runs, like any other file of a
 * model; this only lays its files out.
 *
 * Every entry lands under the model folder or the unpack fails: a name that
 * climbs out with `..` or starts at the root is refused. The single folder
 * Vosk wraps every model in (`vosk-model-small-de-0.15/`) is dropped, so the
 * model's `am/`, `conf/` and `graph/` sit straight in the folder. The files
 * go to a scratch folder first and move into place only once all of them are
 * out, so a model half unpacked is never taken for one.
 */
object EtArchive {

    /** Scratch folder inside the model folder, wiped before each unpack. */
    private const val SCRATCH = ".unpacking"

    /** The file beside [archive] that says it was unpacked whole. */
    fun mark(dir: File, archive: EtFile): File = File(dir, "${archive.name}.unpacked")

    /**
     * Unpacks [zip] into [dir], writes [archive]'s mark, and deletes [zip].
     * Throws [IOException] for an archive that cannot be laid out safely.
     */
    fun unpack(zip: File, dir: File, archive: EtFile) {
        val scratch = File(dir, SCRATCH)
        scratch.deleteRecursively()
        check(scratch.mkdirs()) { "could not make $scratch" }
        try {
            val names = extract(zip, scratch)
            val strip = commonFolder(names)
            val from = if (strip != null) File(scratch, strip) else scratch
            for (child in from.listFiles().orEmpty()) {
                val target = File(dir, child.name)
                target.deleteRecursively()
                if (!child.renameTo(target)) throw IOException("could not move ${child.name} into place")
            }
            mark(dir, archive).writeText(archive.sha256)
            zip.delete()
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** Extracts [zip] under [into]; the entry names, folders with their trailing slash dropped. */
    internal fun extract(zip: File, into: File): List<String> {
        val root = into.canonicalFile
        val names = ArrayList<String>()
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name.replace('\\', '/').trimEnd('/')
                if (name.isEmpty()) continue
                val target = File(root, name).canonicalFile
                if (name.startsWith("/") || !target.path.startsWith(root.path + File.separator)) {
                    throw IOException("archive entry outside the model folder: ${entry.name}")
                }
                names += name
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
        return names
    }

    /** The one top-level folder every entry of [names] is under, or null when there is none. */
    internal fun commonFolder(names: List<String>): String? {
        if (names.isEmpty()) return null
        val top = names.map { it.substringBefore('/') }.distinct().singleOrNull() ?: return null
        // A lone file at the top is not a folder to strip.
        return top.takeIf { names.any { it.startsWith("$top/") } }
    }
}

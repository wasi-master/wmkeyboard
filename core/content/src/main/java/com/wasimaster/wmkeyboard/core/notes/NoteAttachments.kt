package com.wasimaster.wmkeyboard.core.notes

import java.io.File
import java.security.SecureRandom

/**
 * The pictures and drawings in notes (#1586): files in [DIR], beside
 * `notes.json`, written into a note's text as `![](attachments/<name>)`.
 *
 * A relative address on purpose. A note exported as a zip carries an
 * `attachments/` folder beside its `.md` files, so the same text shows its
 * pictures in Obsidian or any Markdown reader; a backup and a sync carry
 * the files by name inside the Notes section.
 *
 * Names are random and of one shape ([isName]), so a name read out of a
 * backup or a note can never point outside the folder.
 */
object NoteAttachments {

    /** The folder, under the app's files. */
    const val DIR = "notes/attachments"

    /** How a note's text addresses a file in [DIR]. */
    const val URL_PREFIX = "attachments/"

    /** Largest file kept: a photo is scaled down well below this before it is stored. */
    const val MAX_BYTES = 12L * 1024 * 1024

    /** How long an unreferenced file is kept before [sweep] takes it: an editor open elsewhere may be about to save it. */
    const val SWEEP_GRACE_MILLIS = 2L * 24 * 60 * 60 * 1000

    private val NAME by lazy { Regex("""^[a-z0-9]{8,40}\.(jpg|jpeg|png|webp)$""") }
    private val REFERENCE by lazy { Regex("""\]\(\s*<?attachments/([A-Za-z0-9._-]+)>?""") }
    private val random = SecureRandom()

    fun isName(name: String): Boolean = NAME.matches(name)

    fun dir(filesDir: File): File = File(filesDir, DIR)

    /** The file a note's address or a bare name stands for, or null when it is not one of ours. */
    fun file(filesDir: File, ref: String): File? {
        val name = nameOf(ref) ?: return null
        return File(dir(filesDir), name)
    }

    /** `attachments/abc.jpg` or `abc.jpg` as the bare name, or null. */
    fun nameOf(ref: String): String? {
        val name = ref.trim().removePrefix("./").removePrefix(URL_PREFIX)
        return name.takeIf(::isName)
    }

    /** Every attachment [body] shows. */
    fun referenced(body: String): Set<String> {
        if (URL_PREFIX !in body) return emptySet()
        return REFERENCE.findAll(body).mapNotNull { nameOf(it.groupValues[1]) }.toSet()
    }

    /** A fresh name with [extension] (`jpg`, `png`, `webp`). */
    fun newName(extension: String): String {
        val bytes = ByteArray(10)
        random.nextBytes(bytes)
        val stem = bytes.joinToString("") { "%02x".format(it) }
        return "$stem.${extension.lowercase().removePrefix(".")}"
    }

    /** [bytes] stored under a new name; the name, or null when they could not be written. */
    fun save(filesDir: File, bytes: ByteArray, extension: String): String? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val name = newName(extension)
        if (!isName(name)) return null
        return runCatching {
            val d = dir(filesDir).apply { mkdirs() }
            File(d, name).writeBytes(bytes)
            name
        }.getOrNull()
    }

    /**
     * Writes a file a backup or a zip carried, under its own name. Only a
     * name of our shape is taken, and a file already there is left alone:
     * names are random, so the same name is the same picture.
     */
    fun restore(filesDir: File, name: String, bytes: ByteArray): Boolean {
        if (!isName(name) || bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        return runCatching {
            val d = dir(filesDir).apply { mkdirs() }
            val target = File(d, name)
            if (target.parentFile?.canonicalPath != d.canonicalPath) return false
            if (!target.exists()) target.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    /**
     * Deletes the files no note in [bodies] shows any more (the trash
     * counts), once they are older than [graceMillis]. Returns how many went.
     */
    fun sweep(filesDir: File, bodies: Sequence<String>, now: Long = System.currentTimeMillis(), graceMillis: Long = SWEEP_GRACE_MILLIS): Int {
        val files = dir(filesDir).listFiles()?.filter { it.isFile } ?: return 0
        if (files.isEmpty()) return 0
        val used = HashSet<String>()
        for (body in bodies) used += referenced(body)
        var removed = 0
        for (f in files) {
            if (f.name in used) continue
            if (now - f.lastModified() < graceMillis) continue
            if (f.delete()) removed++
        }
        return removed
    }
}

package com.wasimaster.wmkeyboard.core.input.rime

import com.wasimaster.wmkeyboard.core.input.composer.CjkDictPack
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * RIME's files on the device, all under `filesDir/rime/`:
 *
 * - `shared/`: the shared data directory librime reads schemas from. Every
 *   installed pack and every imported schema zip is unpacked into it.
 * - `user/`: librime's user directory: the user dictionaries it learns into
 *   (`*.userdb`), `installation.yaml`, the generated `default.custom.yaml`
 *   and the compiled schemas under `build/`.
 * - `owners/<owner>.files`: which files of `shared/` each pack or import put
 *   there, first line its source's SHA-256, so removing one takes only its
 *   own files and a pack is unpacked again only when its bytes changed.
 * - `deployed`: what the last deploy compiled, first line the [desiredToken]
 *   it was for, then one schema id per line.
 *
 * Credential-protected storage on purpose: the user dictionary is the
 * user's typing. Before the first unlock none of this can be read, and the
 * keyboard types through the built-in composers until it can.
 */
object RimeStore {

    /** Owners of downloaded catalog packs are their pack id; imports carry this prefix. */
    private const val IMPORT_PREFIX = "import-"

    /** Files a pack may not put down: the generated configuration is ours. */
    private val RESERVED = setOf("default.custom.yaml", "installation.yaml", "user.yaml")

    /** An imported zip expanding to more than this is refused. */
    private const val MAX_IMPORT_BYTES = 512L * 1024 * 1024

    fun root(filesDir: File) = File(filesDir, "rime")
    fun sharedDir(filesDir: File) = File(root(filesDir), "shared")
    fun userDir(filesDir: File) = File(root(filesDir), "user")
    private fun ownersDir(filesDir: File) = File(root(filesDir), "owners")
    private fun deployedFile(filesDir: File) = File(root(filesDir), "deployed")

    /** The catalog packs whose verified download is on the device. */
    fun downloaded(filesDir: File): List<CjkDictPack> =
        RimeSchemaCatalog.packs.filter { CjkDictStore.isDownloaded(filesDir, it) }

    /**
     * What the user made in RIME, relative to [root]: the words it learned
     * (`user/`) and the zips they imported, with their manifests. The one part
     * no download gives back (#1045). The catalog packs stay out.
     */
    fun userLayer(filesDir: File): List<String> {
        val root = root(filesDir)
        val words = userDir(filesDir).walkTopDown().filter(File::isFile).map { it.relativeTo(root).invariantSeparatorsPath }
        val manifests = ownersDir(filesDir).listFiles().orEmpty().filter { it.name.startsWith(IMPORT_PREFIX) }
        val imported = manifests.flatMap { it.readLines().drop(1) }.filter(String::isNotBlank).map { "shared/$it" }
        return (words + manifests.map { it.relativeTo(root).invariantSeparatorsPath } + imported).distinct().toList()
    }

    /** Writes back one file of [userLayer]; anything else, or outside [root], is refused. */
    fun restoreUserLayer(filesDir: File, relative: String, bytes: ByteArray): Boolean {
        val root = root(filesDir).canonicalFile
        val target = File(root, relative).canonicalFile
        val inLayer = relative.startsWith("user/") || relative.startsWith("shared/") ||
            relative.startsWith("owners/$IMPORT_PREFIX")
        if (!inLayer || !target.startsWith(root)) return false
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return true
    }

    /** Names of the user's imported schema zips, as imported. */
    fun imports(filesDir: File): List<String> =
        ownersDir(filesDir).listFiles().orEmpty()
            .map { it.name.removeSuffix(".files") }
            .filter { it.startsWith(IMPORT_PREFIX) }
            .map { it.removePrefix(IMPORT_PREFIX) }
            .sorted()

    /**
     * What a deploy now would compile from: the downloaded packs by checksum
     * and the imports by their manifests. A deploy records it, and the engine
     * is ready only while the recorded one still matches.
     */
    fun desiredToken(filesDir: File): String {
        val parts = downloaded(filesDir).map { "${it.id}=${it.sha256}" } +
            imports(filesDir).map { name -> "import:$name=${ownerFile(filesDir, IMPORT_PREFIX + name).readLines().firstOrNull()}" }
        if (parts.isEmpty()) return ""
        return sha256(parts.joinToString("\n").toByteArray())
    }

    /** The token and schema ids of the last deploy, or null when there was none. */
    fun deployed(filesDir: File): Pair<String, List<String>>? {
        val lines = deployedFile(filesDir).takeIf(File::isFile)?.readLines() ?: return null
        val token = lines.firstOrNull() ?: return null
        return token to lines.drop(1).filter(String::isNotBlank)
    }

    internal fun recordDeploy(filesDir: File, token: String, schemas: List<String>) {
        writeAtomically(deployedFile(filesDir), (listOf(token) + schemas).joinToString("\n") + "\n")
    }

    internal fun clearDeploy(filesDir: File) {
        deployedFile(filesDir).delete()
    }

    /**
     * Brings `shared/` in line with what is installed: unpacks each
     * downloaded pack whose recorded checksum differs, and removes the files
     * of packs no longer downloaded. Imports are unpacked when imported, so
     * they are left as they are.
     */
    internal fun syncShared(filesDir: File) {
        val shared = sharedDir(filesDir).apply { mkdirs() }
        val packs = downloaded(filesDir)
        val wanted = packs.map { it.id }.toSet()
        for (owner in ownersDir(filesDir).listFiles().orEmpty()) {
            val name = owner.name.removeSuffix(".files")
            if (!name.startsWith(IMPORT_PREFIX) && name !in wanted) removeOwner(filesDir, name)
        }
        for (pack in packs) {
            val recorded = ownerFile(filesDir, pack.id).takeIf(File::isFile)?.readLines()?.firstOrNull()
            if (recorded == pack.sha256) continue
            removeOwner(filesDir, pack.id)
            val zip = CjkDictStore.packFile(filesDir, pack)
            val files = unpack(zip, shared, root = "", maxBytes = Long.MAX_VALUE, skip = RESERVED)
            writeOwner(filesDir, pack.id, pack.sha256, files)
        }
    }

    /** The result of [import]: the schema ids it brought, and files it left out. */
    data class Imported(val name: String, val schemas: List<String>, val skipped: List<String>)

    /**
     * Installs a schema zip of the user's own: the files under the folder
     * that holds its `*.schema.yaml`, wherever that folder is inside the zip.
     * A file a catalog pack already put down is not replaced (the schemas
     * that pack serves depend on it), and neither is RIME's configuration.
     * Throws [IOException] for a zip with no schema in it.
     */
    fun import(filesDir: File, zip: File, displayName: String): Imported {
        val name = displayName.substringAfterLast('/').removeSuffix(".zip").replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "schema" }
        val root = ZipFile(zip).use { file ->
            val schema = file.entries().asSequence().map { it.name }
                .filter { it.endsWith(".schema.yaml") && !it.contains("__MACOSX") }
                .minByOrNull { it.count { c -> c == '/' } }
                ?: throw IOException("no .schema.yaml in $displayName")
            schema.substringBeforeLast('/', "")
        }
        removeOwner(filesDir, IMPORT_PREFIX + name)
        val taken = ownersDir(filesDir).listFiles().orEmpty()
            .filterNot { it.name.startsWith(IMPORT_PREFIX) }
            .flatMap { it.readLines().drop(1) }
            .toSet()
        val skipped = ArrayList<String>()
        val files = unpack(zip, sharedDir(filesDir).apply { mkdirs() }, root, MAX_IMPORT_BYTES, RESERVED + "default.yaml") { path ->
            (path in taken).also { if (it) skipped += path }
        }
        writeOwner(filesDir, IMPORT_PREFIX + name, sha256(zip), files)
        val schemas = files.filter { '/' !in it && it.endsWith(".schema.yaml") }
            .mapNotNull { schemaId(File(sharedDir(filesDir), it)) }
        return Imported(name, schemas, skipped)
    }

    /** Removes an imported zip's files. */
    fun removeImport(filesDir: File, name: String) {
        removeOwner(filesDir, IMPORT_PREFIX + name)
    }

    /** The schema files to compile, top level of `shared/`, by name for a stable order. */
    internal fun schemaFiles(filesDir: File): List<File> =
        sharedDir(filesDir).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".schema.yaml") }
            .sortedBy { it.name }

    /**
     * The id a schema file declares (`schema: schema_id: …`), read without a
     * YAML parser: the key is always near the top and on its own line.
     */
    fun schemaId(file: File): String? = file.useLines { lines ->
        lines.take(LINES_TO_SCAN).firstNotNullOfOrNull { line ->
            SCHEMA_ID.find(line)?.groupValues?.get(1)?.trim('"', '\'')
        }
    } ?: file.name.removeSuffix(".schema.yaml").takeIf { it.isNotBlank() }

    /** The display name a schema file declares (`name: …`), if any. */
    fun schemaName(file: File): String? = file.useLines { lines ->
        lines.take(LINES_TO_SCAN).firstNotNullOfOrNull { line ->
            SCHEMA_NAME.find(line)?.groupValues?.get(1)?.trim()?.trim('"', '\'')?.takeIf(String::isNotBlank)
        }
    }

    private const val LINES_TO_SCAN = 40
    private val SCHEMA_ID = Regex("""^\s+schema_id:\s*(\S+)""")
    private val SCHEMA_NAME = Regex("""^\s+name:\s*(.+?)\s*(#.*)?$""")

    /**
     * Writes `user/default.custom.yaml`, putting every compiled schema in the
     * schema list, so librime lists the user's imports as well as the packs'.
     */
    internal fun writeSchemaList(filesDir: File, schemas: List<String>) {
        val text = buildString {
            append("# Written by WM Keyboard on every RIME deploy; edits are overwritten.\n")
            append("patch:\n  schema_list:\n")
            for (id in schemas) append("    - schema: ").append(id).append('\n')
        }
        writeAtomically(File(userDir(filesDir).apply { mkdirs() }, "default.custom.yaml"), text)
    }

    /** Wipes everything, user dictionaries included: what "delete all" means. */
    fun deleteAll(filesDir: File) {
        root(filesDir).deleteRecursively()
    }

    /** Bytes on disk, for the storage screen. */
    fun size(filesDir: File): Long = root(filesDir).walkBottomUp().filter(File::isFile).sumOf(File::length)

    private fun ownerFile(filesDir: File, owner: String) = File(ownersDir(filesDir), "$owner.files")

    private fun writeOwner(filesDir: File, owner: String, sha: String, files: List<String>) {
        writeAtomically(ownerFile(filesDir, owner).apply { parentFile?.mkdirs() }, (listOf(sha) + files).joinToString("\n") + "\n")
    }

    /** Deletes an owner's files that no other owner also lists, then its manifest. */
    private fun removeOwner(filesDir: File, owner: String) {
        val manifest = ownerFile(filesDir, owner)
        if (!manifest.isFile) return
        val mine = manifest.readLines().drop(1).filter(String::isNotBlank)
        val others = ownersDir(filesDir).listFiles().orEmpty()
            .filter { it != manifest }
            .flatMap { it.readLines().drop(1) }
            .toSet()
        val shared = sharedDir(filesDir)
        for (path in mine) if (path !in others) File(shared, path).delete()
        manifest.delete()
    }

    /**
     * Extracts the files of [zip] under [root] into [dest], refusing any
     * entry that would land outside it. Returns the paths written, relative
     * to [dest]. [skip] names leaf files never written; [veto] can refuse a
     * path too.
     */
    private fun unpack(
        zip: File,
        dest: File,
        root: String,
        maxBytes: Long,
        skip: Set<String>,
        veto: (String) -> Boolean = { false },
    ): List<String> {
        val written = ArrayList<String>()
        val canonicalDest = dest.canonicalPath + File.separator
        var total = 0L
        ZipFile(zip).use { file ->
            for (entry in file.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                if (root.isNotEmpty() && !name.startsWith("$root/")) continue
                val path = if (root.isEmpty()) name else name.removePrefix("$root/")
                if (path.isEmpty() || path.startsWith("licenses/") || path.contains("__MACOSX")) continue
                if (path.substringAfterLast('/') in skip || path.substringAfterLast('/').startsWith(".")) continue
                if (veto(path)) continue
                val target = File(dest, path)
                if (!target.canonicalPath.startsWith(canonicalDest)) throw IOException("zip entry outside the folder: $name")
                target.parentFile?.mkdirs()
                file.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) throw IOException("zip expands past ${maxBytes / (1024 * 1024)} MB")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                written += path
            }
        }
        return written
    }

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not write ${file.name}" }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

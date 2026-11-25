package com.wasimaster.wmkeyboard.core.tools.offlinegif

import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.os.StatFs
import com.wasimaster.wmkeyboard.core.tools.GifItem
import com.wasimaster.wmkeyboard.core.tools.GifSource
import com.wasimaster.wmkeyboard.core.tools.GifSources
import com.wasimaster.wmkeyboard.core.tools.MediaCategory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * GIF packs kept on the device, for a GIF panel that works with no network at
 * all ([GifSource.OFFLINE]).
 *
 * The format is CleverKeys' offline GIF pack, so the packs published at
 * [PACKS_PAGE] import as they are: a zip holding `manifest.json`
 * (`pack_id`, `name`, `gif_count`), `pack.db` (SQLite: `gifs` with
 * `gif_id`, `width`, `height`, `search_text`, plus `categories` and
 * `gif_category_map`), and the images under `full/<id / 1000>/<id>.webp`
 * (animated) and `thumbs/…` (80 px stills). A `.gif` in `full/` is taken too.
 *
 * A pack can also be the user's own pictures ([importImages]): a zip, or a
 * pick of loose files, of plain `.gif` and animated `.webp` files. It gets a
 * `pack.db` of the same schema written here, the words of each file name as
 * its search text, and each sub-folder as a category. Every file keeps its
 * own extension, so a GIF is sent as `image/gif`.
 *
 * Only a GIF with an animated file is kept. The thumbnail-only packs carry
 * nothing that can be sent, only an 80 px still of it, so a pack that is all
 * thumbnails is refused ([ImportResult.PreviewsOnly]) rather than installed
 * as a grid that does nothing when tapped. The stills are not kept either: the
 * grid previews with the animated file, as it does for the user's stickers.
 *
 * Each pack lives in `filesDir/gif_packs/<pack_id>/` with its `pack.db`
 * trimmed to the GIFs it really has, so a search never offers a file that is
 * not there. Process-wide: the keyboard searches and the settings page
 * imports, and they are one process.
 */
object OfflineGifPacks {

    /** CleverKeys' release of offline GIF packs. */
    const val PACKS_PAGE = "https://github.com/tribixbite/CleverKeys/releases/tag/CleverKeys-GIF"

    private const val DIR_NAME = "gif_packs"
    private const val MANIFEST = "manifest.json"
    private const val DATABASE = "pack.db"

    /** The two entries every pack has, for telling one from its entry names alone. */
    const val MANIFEST_NAME = MANIFEST
    const val DATABASE_NAME = DATABASE
    private const val ITEM_PREFIX = "offline:"
    private const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024
    private val PACK_ID = Regex("[A-Za-z0-9._-]{1,64}")
    private val FULL_ENTRY = Regex("""full/(?:\d+/)?(\d+)\.(webp|gif)""", RegexOption.IGNORE_CASE)

    /** The extensions a picture of the user's own can come as, in the order a pack's file is looked for. */
    private val IMAGE_EXTENSIONS = listOf("webp", "gif")

    private val json = Json { ignoreUnknownKeys = true }

    data class Pack(val id: String, val name: String, val gifCount: Int, val sizeBytes: Long)

    sealed interface ImportResult {
        /** [added] is how many GIFs this import brought, which for a pack added to is fewer than it holds. */
        data class Imported(val pack: Pack, val added: Int = pack.gifCount) : ImportResult

        /** A thumbnail pack: stills to look at, nothing to send. */
        data class PreviewsOnly(val name: String) : ImportResult
        data object NoSpace : ImportResult
        data object NotAPack : ImportResult

        /** Pictures were offered, but none of them was a GIF or WebP that could be read. */
        data object NoPictures : ImportResult
    }

    @Volatile
    private var root: File? = null

    @Volatile
    private var cached: List<Pack>? = null

    /** Open read-only handles, one per pack, kept for the next search. */
    private val databases = HashMap<String, SQLiteDatabase>()

    /**
     * Points the store at the app's files. Idempotent; the keyboard and the
     * settings screen both call it, whichever comes first.
     */
    fun attach(filesDir: File) {
        val next = File(filesDir, DIR_NAME)
        if (next == root) return
        root = next
        cached = null
        synchronized(databases) {
            databases.values.forEach { it.close() }
            databases.clear()
        }
    }

    /** Whether any pack is installed, for the GIF panel's source list. */
    val hasPacks: Boolean get() = packs().isNotEmpty()

    fun packs(): List<Pack> {
        cached?.let { return it }
        val dir = root ?: return emptyList()
        val found = dir.listFiles { file -> file.isDirectory && PACK_ID.matches(file.name) }
            .orEmpty()
            .mapNotNull { readPack(it) }
            .sortedBy { it.name.lowercase() }
        cached = found
        return found
    }

    private fun readPack(dir: File): Pack? {
        val db = File(dir, DATABASE)
        val manifest = runCatching { json.parseToJsonElement(File(dir, MANIFEST).readText()) as JsonObject }
            .getOrNull() ?: return null
        if (!db.isFile) return null
        val name = manifest["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: dir.name
        val count = manifest["gif_count"]?.jsonPrimitive?.intOrNull ?: 0
        val size = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        return Pack(dir.name, name, count, size)
    }

    /**
     * Whether [zip] is a GIF pack at all — its manifest names a pack and a
     * database sits beside it — so an importer that sees many kinds of zip can
     * ask before handing it over.
     */
    fun isPack(zip: ZipFile): Boolean = zip.getEntry(MANIFEST) != null && zip.getEntry(DATABASE) != null &&
        runCatching { manifestOf(zip)["pack_id"] }.getOrNull() != null

    private fun manifestOf(zip: ZipFile): JsonObject =
        zip.getInputStream(zip.getEntry(MANIFEST)).use { input ->
            json.parseToJsonElement(input.readBytes().decodeToString()) as JsonObject
        }

    /**
     * Installs the pack in [zipFile], replacing one with the same `pack_id`.
     * Nothing is visible until the whole pack is in place: it is unpacked
     * beside the others and renamed in at the end.
     */
    @Synchronized
    fun import(filesDir: File, zipFile: File): ImportResult {
        attach(filesDir)
        val base = root!!.apply { mkdirs() }
        ZipFile(zipFile).use { zip ->
            if (!isPack(zip)) return ImportResult.NotAPack
            val manifest = manifestOf(zip)
            val id = manifest["pack_id"]?.jsonPrimitive?.content?.takeIf { PACK_ID.matches(it) }
                ?: return ImportResult.NotAPack
            val name = manifest["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: id
            val animated = zip.entries().asSequence().filter { !it.isDirectory && FULL_ENTRY.matches(it.name) }.toList()
            if (animated.isEmpty()) return ImportResult.PreviewsOnly(name)
            val needed = animated.sumOf { it.size.coerceAtLeast(0) } + zip.getEntry(DATABASE).size
            if (StatFs(base.path).availableBytes < needed + SPACE_MARGIN_BYTES) return ImportResult.NoSpace

            val staging = File(base, ".$id.part").apply { deleteRecursively() }
            try {
                staging.mkdirs()
                copyEntry(zip, MANIFEST, File(staging, MANIFEST))
                copyEntry(zip, DATABASE, File(staging, DATABASE))
                val ids = HashSet<Long>(animated.size)
                for (entry in animated) {
                    val match = FULL_ENTRY.matchEntire(entry.name)!!.groupValues
                    val gifId = match[1].toLong()
                    // Our own path from the id, never the entry's name: a
                    // crafted `full/../../x.webp` cannot match FULL_ENTRY, but
                    // the file lands where fileOf() will look regardless.
                    zip.getInputStream(entry).use { input ->
                        fullFile(staging, gifId, match[2].lowercase()).apply { parentFile?.mkdirs() }
                            .outputStream().use { input.copyTo(it) }
                    }
                    ids += gifId
                }
                val kept = trimTo(File(staging, DATABASE), ids)
                if (kept == 0) return ImportResult.PreviewsOnly(name)
                // The count the panel shows is what is really here.
                File(staging, MANIFEST).writeText(JsonObject(manifest + ("gif_count" to JsonPrimitive(kept))).toString())
                close(id)
                val target = File(base, id)
                target.deleteRecursively()
                if (!staging.renameTo(target)) throw IOException("could not move the pack into place")
                cached = null
                return ImportResult.Imported(readPack(target)!!)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    /** One picture offered for a pack of the user's own: where it sits in the zip or the pick, and how to read it. */
    class ImageSource(val path: String, val size: Long, val open: () -> InputStream)

    /** Files any zip of pictures may carry beside them, passed over without making it something else. */
    private val IGNORABLE_LEAF =
        Regex("""(?i)^(\..*|thumbs\.db|desktop\.ini|(readme|license|licence|credits|notice)([._-].*)?)$""")

    private fun ignorable(path: String): Boolean =
        path.startsWith("__MACOSX/") || IGNORABLE_LEAF.matches(path.substringAfterLast('/'))

    /** Whether [path] is a picture a pack of the user's own can hold: a `.gif` or a `.webp`. */
    fun isImageName(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS && !ignorable(path)

    /**
     * Whether a zip with these entries is a pack of the user's own pictures.
     * [lenient] is the GIF pack screen's import, which takes a zip holding any
     * GIF or WebP and passes over its other files. Anywhere else only a zip of
     * nothing but `.gif` files counts: a zip of WebP pictures is as likely a
     * sticker pack's, and one with other files in it is not clearly a GIF pack.
     */
    fun isImageArchive(entryNames: List<String>, lenient: Boolean): Boolean {
        val files = entryNames.filter { !it.endsWith('/') && !ignorable(it) }
        if (files.isEmpty()) return false
        return if (lenient) files.any(::isImageName) else files.all { it.endsWith(".gif", ignoreCase = true) }
    }

    /**
     * The id a pack of the user's own named [name] is kept under. Prefixed so
     * it never replaces a CleverKeys pack; a name with no Latin letters or
     * digits in it is told apart by its hash.
     */
    fun ownPackId(name: String): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').take(OWN_SLUG_LENGTH)
        return OWN_PREFIX + slug.ifEmpty { Integer.toHexString(name.hashCode()) }
    }

    /** [importImages] for a zip of pictures, the pack named [name] (the zip's own name, as a rule). */
    fun importImageZip(filesDir: File, zipFile: File, name: String): ImportResult = ZipFile(zipFile).use { zip ->
        val sources = zip.entries().asSequence()
            .filter { !it.isDirectory && isImageName(it.name) }
            .map { entry -> ImageSource(entry.name, entry.size) { zip.getInputStream(entry) } }
            .toList()
        importImages(filesDir, ownPackId(name), name, sources)
    }

    /**
     * Makes a pack named [name] of [images], the user's own GIF and WebP
     * files. A pack already kept as [id] is replaced, or with [append] added
     * to, which is how loose files picked on their own gather in one pack.
     * Each file's name gives its search words, and its first folder its
     * category (below one folder every file shares, which is what zipping a
     * folder adds). A file that does not read as a picture is left out.
     * Nothing is visible until a new pack is whole: it is built beside the
     * others and renamed in at the end, as [import] does.
     */
    @Synchronized
    fun importImages(
        filesDir: File,
        id: String,
        name: String,
        images: List<ImageSource>,
        append: Boolean = false,
    ): ImportResult {
        attach(filesDir)
        val base = root!!.apply { mkdirs() }
        if (!PACK_ID.matches(id)) return ImportResult.NotAPack
        val pictures = images.filter { isImageName(it.path) }.sortedBy { it.path.lowercase() }
        if (pictures.isEmpty()) return ImportResult.NoPictures
        val needed = pictures.sumOf { it.size.coerceAtLeast(0) }
        if (StatFs(base.path).availableBytes < needed + SPACE_MARGIN_BYTES) return ImportResult.NoSpace
        val target = File(base, id)
        val adding = append && File(target, DATABASE).isFile && File(target, MANIFEST).isFile
        close(id)
        val dir = if (adding) target else File(base, ".$id.part").apply { deleteRecursively(); mkdirs() }
        val written = ArrayList<File>()
        var finished = false
        try {
            val (added, total) = SQLiteDatabase.openOrCreateDatabase(File(dir, DATABASE), null).use { db ->
                if (!adding) createTables(db)
                val added = writePictures(db, dir, pictures, written)
                added to db.rawQuery("SELECT COUNT(*) FROM gifs", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            }
            if (added == 0) return ImportResult.NoPictures
            val manifest = mapOf(
                "pack_id" to JsonPrimitive(id),
                "name" to JsonPrimitive(name),
                "gif_count" to JsonPrimitive(total),
                "own" to JsonPrimitive(true),
            )
            File(dir, MANIFEST).writeText(JsonObject(manifest).toString())
            if (!adding) {
                target.deleteRecursively()
                if (!dir.renameTo(target)) throw IOException("could not move the pack into place")
            }
            cached = null
            finished = true
            return ImportResult.Imported(readPack(target)!!, added)
        } finally {
            // A pack added to keeps what it had; only this import's files go.
            if (!adding) dir.deleteRecursively() else if (!finished) written.forEach { it.delete() }
        }
    }

    /** CleverKeys' three tables, with the columns its packs have. */
    private fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS categories (category_id INTEGER PRIMARY KEY, name TEXT, icon TEXT, sort_order INTEGER)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS gifs (gif_id INTEGER PRIMARY KEY, width INTEGER, height INTEGER, " +
                "duration_ms INTEGER, file_size INTEGER, pack_id INTEGER, search_text TEXT, created_at INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS gif_category_map (category_id INTEGER, gif_id INTEGER, " +
                "PRIMARY KEY (category_id, gif_id))",
        )
    }

    /**
     * Copies [pictures] into [dir] under the next free ids and adds a row for
     * each that reads as a picture. Every file written goes into [written], for
     * the caller to take away again if the import fails. Returns how many were added.
     */
    private fun writePictures(
        db: SQLiteDatabase,
        dir: File,
        pictures: List<ImageSource>,
        written: MutableList<File>,
    ): Int {
        val shared = sharedFolder(pictures.map { it.path })
        val categories = HashMap<String, Long>()
        db.rawQuery("SELECT category_id, name FROM categories", null).use { cursor ->
            while (cursor.moveToNext()) categories[cursor.getString(1).orEmpty().lowercase()] = cursor.getLong(0)
        }
        var nextCategory = (categories.values.maxOrNull() ?: 0L) + 1
        var nextId = db.rawQuery("SELECT COALESCE(MAX(gif_id), 0) FROM gifs", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } + 1
        var added = 0
        db.beginTransaction()
        try {
            for (picture in pictures) {
                val extension = picture.path.substringAfterLast('.').lowercase()
                // Our own path from the id, never the entry's name.
                val file = fullFile(dir, nextId, extension).apply { parentFile?.mkdirs() }
                picture.open().use { input -> file.outputStream().use { input.copyTo(it) } }
                written += file
                val size = sizeOf(file)
                if (size == null) {
                    file.delete()
                    continue
                }
                val relative = picture.path.removePrefix(shared)
                val folder = relative.substringBeforeLast('/', "").substringBefore('/').trim()
                val text = listOf(searchWords(relative.substringAfterLast('/').substringBeforeLast('.')), searchWords(folder))
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                db.execSQL(
                    "INSERT INTO gifs (gif_id, width, height, file_size, search_text) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any>(nextId, size.first, size.second, file.length(), text),
                )
                if (folder.isNotEmpty()) {
                    val categoryId = categories.getOrPut(folder.lowercase()) {
                        val newId = nextCategory++
                        db.execSQL(
                            "INSERT INTO categories (category_id, name, sort_order) VALUES (?, ?, ?)",
                            arrayOf<Any>(newId, folder, newId),
                        )
                        newId
                    }
                    db.execSQL(
                        "INSERT OR IGNORE INTO gif_category_map (category_id, gif_id) VALUES (?, ?)",
                        arrayOf<Any>(categoryId, nextId),
                    )
                }
                nextId++
                added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    /** The one folder every path sits in, with its slash, or "" when they do not share one. */
    private fun sharedFolder(paths: List<String>): String {
        val first = paths.firstOrNull()?.substringBefore('/', "").orEmpty()
        if (first.isEmpty()) return ""
        return if (paths.all { it.startsWith("$first/") }) "$first/" else ""
    }

    /** A file or folder name as search words: `catDance_02` reads `cat dance 02`. */
    internal fun searchWords(name: String): String =
        name.replace(CAMEL_HUMP, "$1 $2").lowercase()
            .split(NOT_A_WORD)
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private val CAMEL_HUMP = Regex("([a-z])([A-Z])")
    private val NOT_A_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** A picture's width and height, read from its header without decoding it; null when it is not one. */
    private fun sizeOf(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.path, options) }
        if (options.outWidth > 0 && options.outHeight > 0) return options.outWidth to options.outHeight
        return headerSize(file)
    }

    /**
     * The size a GIF or an extended WebP states in its header, for a decoder
     * that says nothing (an animated WebP on an older Android): a GIF's logical
     * screen, a `VP8X` chunk's canvas.
     */
    private fun headerSize(file: File): Pair<Int, Int>? {
        val head = ByteArray(HEADER_BYTES)
        val read = file.inputStream().use { it.read(head) }
        fun byte(at: Int) = head[at].toInt() and 0xFF
        fun ascii(from: Int, length: Int) = String(head, from, length, Charsets.US_ASCII)
        val size = when {
            read >= 10 && ascii(0, 4) == "GIF8" -> (byte(6) or (byte(7) shl 8)) to (byte(8) or (byte(9) shl 8))
            read >= 30 && ascii(0, 4) == "RIFF" && ascii(8, 8) == "WEBPVP8X" ->
                (1 + (byte(24) or (byte(25) shl 8) or (byte(26) shl 16))) to
                    (1 + (byte(27) or (byte(28) shl 8) or (byte(29) shl 16)))
            else -> return null
        }
        return size.takeIf { it.first > 0 && it.second > 0 }
    }

    private const val HEADER_BYTES = 30
    private const val OWN_PREFIX = "own-"
    private const val OWN_SLUG_LENGTH = 60

    private fun copyEntry(zip: ZipFile, name: String, target: File) {
        zip.getInputStream(zip.getEntry(name)).use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    /** Drops every row in [db] that has no animated file, and returns how many remain. */
    private fun trimTo(db: File, ids: Set<Long>): Int {
        val database = SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val missing = ArrayList<Long>()
            database.rawQuery("SELECT gif_id FROM gifs", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val gifId = cursor.getLong(0)
                    if (gifId !in ids) missing += gifId
                }
            }
            database.beginTransaction()
            try {
                for (gifId in missing) {
                    val args = arrayOf<Any>(gifId)
                    database.execSQL("DELETE FROM gifs WHERE gif_id = ?", args)
                    runCatching { database.execSQL("DELETE FROM gif_category_map WHERE gif_id = ?", args) }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            return database.rawQuery("SELECT COUNT(*) FROM gifs", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } finally {
            database.close()
        }
    }

    @Synchronized
    fun delete(id: String) {
        val base = root ?: return
        if (!PACK_ID.matches(id)) return
        close(id)
        File(base, id).deleteRecursively()
        cached = null
    }

    private fun close(id: String) {
        synchronized(databases) { databases.remove(id)?.close() }
    }

    /**
     * Closes every open pack handle. Each one is a live SQLite connection with
     * a page cache behind it, held for the next search — worth it while the
     * GIF panel is in use and not worth it for the rest of the process's life,
     * which is how long they lasted before. [database] reopens on demand, so
     * the only cost of being wrong is one open on the next search.
     */
    fun releaseDatabases() {
        synchronized(databases) {
            databases.values.forEach { it.close() }
            databases.clear()
        }
    }

    private fun database(id: String): SQLiteDatabase? {
        val base = root ?: return null
        synchronized(databases) {
            databases[id]?.let { return it }
            val file = File(File(base, id), DATABASE)
            if (!file.isFile) return null
            return runCatching {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            }.getOrNull()?.also { databases[id] = it }
        }
    }

    private fun fullFile(packDir: File, gifId: Long, extension: String): File =
        File(File(File(packDir, "full"), "%03d".format(gifId / 1000)), "%06d.%s".format(gifId, extension))

    /** The file a GIF of a pack is kept in, whichever extension it came with. */
    private fun fileOf(packDir: File, gifId: Long): File {
        for (extension in IMAGE_EXTENSIONS) {
            val file = fullFile(packDir, gifId, extension)
            if (file.isFile) return file
        }
        return fullFile(packDir, gifId, IMAGE_EXTENSIONS.first())
    }

    /**
     * Up to [limit] GIFs for [query], taken from the packs in turn so one large
     * pack cannot fill the grid on its own; a blank query lists each pack in
     * its own order. Every word of the query has to appear in a GIF's search
     * text, or the whole query has to name one of its categories, which is how
     * a category chip (a search for its name) finds its GIFs.
     */
    fun search(query: String, limit: Int): List<GifItem> {
        val base = root ?: return emptyList()
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val perPack = ArrayList<List<GifItem>>()
        for (pack in packs()) {
            val results = ArrayList<GifItem>()
            perPack += results
            val db = database(pack.id) ?: continue
            val sql = StringBuilder("SELECT gif_id, width, height, search_text FROM gifs")
            val args = ArrayList<String>()
            if (words.isNotEmpty()) {
                sql.append(" WHERE (")
                words.forEachIndexed { index, word ->
                    if (index > 0) sql.append(" AND ")
                    sql.append("lower(search_text) LIKE ? ESCAPE '\\'")
                    args += "%" + escapeLike(word) + "%"
                }
                sql.append(") OR gif_id IN (SELECT m.gif_id FROM gif_category_map m")
                sql.append(" JOIN categories c ON c.category_id = m.category_id WHERE lower(c.name) = ?)")
                args += words.joinToString(" ")
            }
            sql.append(" ORDER BY rowid LIMIT ").append(limit)
            runCatching {
                db.rawQuery(sql.toString(), args.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        val gifId = cursor.getLong(0)
                        val width = cursor.getInt(1)
                        val height = cursor.getInt(2)
                        val file = fileOf(File(base, pack.id), gifId)
                        val url = "file://" + file.absolutePath
                        results += GifItem(
                            id = "$ITEM_PREFIX${pack.id}:$gifId",
                            previewUrl = url,
                            fullUrl = url,
                            mime = if (file.extension == "gif") "image/gif" else "image/webp",
                            aspectRatio = if (width > 0 && height > 0) width.toFloat() / height else 1f,
                            source = GifSource.OFFLINE,
                            title = cursor.getString(3).orEmpty().split(' ').take(TITLE_WORDS).joinToString(" "),
                        )
                    }
                }
            }
        }
        return GifSources.interleave(perPack).take(limit)
    }

    /** Categories that hold at least one GIF, across every pack, in the packs' own order. */
    fun categories(): List<MediaCategory> {
        val seen = LinkedHashMap<String, MediaCategory>()
        for (pack in packs()) {
            val db = database(pack.id) ?: continue
            runCatching {
                db.rawQuery(
                    "SELECT c.name FROM categories c WHERE EXISTS " +
                        "(SELECT 1 FROM gif_category_map m WHERE m.category_id = c.category_id) " +
                        "ORDER BY c.sort_order",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val label = cursor.getString(0) ?: continue
                        seen.getOrPut(label.lowercase()) { MediaCategory(term = label.lowercase(), label = label) }
                    }
                }
            }
        }
        return seen.values.toList()
    }

    private fun escapeLike(word: String): String =
        word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private const val TITLE_WORDS = 4
}

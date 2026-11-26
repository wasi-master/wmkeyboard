package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.FilePlaces
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.ICloudSession
import com.wasimaster.wmkeyboard.core.settings.sink.S3Listing
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder
import java.text.Normalizer
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source

/**
 * The account's iCloud Drive, from its top, through the private web API
 * iCloud.com calls: the calls [com.wasimaster.wmkeyboard.core.settings.sink.ICloudSink]
 * already makes, plus `renameItems` and `moveItems` as rclone's
 * `iclouddrive` backend and pyicloud send them. Apple can change any of it
 * without notice; a call that then fails throws, and nothing is guessed.
 *
 * Signing in is the sink's: the saved cookies when iCloud still honours
 * them, else the password with the trust token. A fresh sign-in is saved
 * back to the location as the sink saves it.
 *
 * - **Names** are compared as iCloud does, ignoring case ([caseInsensitive]),
 *   after putting iCloud's NFD names into NFC.
 * - **Uploads** go straight to their name ([atomicUpload]): the file is only
 *   in the folder once `update/documents` has run. iCloud does not replace a
 *   file of the same name but keeps both, calling the new one `name 2`, so
 *   the upload is looked up afterwards by its document id; one iCloud
 *   renamed is taken out again (it is ours) and the upload fails.
 * - **Delete** moves to Recently Deleted (`moveItemsToTrash`), as the sink
 *   does, so it can be undone for 30 days. A folder goes only when a fresh
 *   look finds it empty.
 * - **App folders** (`APP_LIBRARY`) and anything that is neither a plain
 *   file nor a folder are browsed but never renamed, moved or deleted.
 * - **Packages** (Pages documents and the like) download only as iCloud's
 *   zip of them, which is not the file; they refuse to download.
 */
class ICloudFs(private val context: Context, private val location: BackupLocation) : RemoteFs {

    private val config = location.icloud

    private val session by lazy {
        ICloudSession(config.appleId, config.clientId.ifEmpty { ICloudSession.CLIENT_ID }, config.cookies, config.trustToken)
    }

    private var services: ICloudSession.Services? = null

    /** One item of a folder, as `retrieveItemDetailsInFolders` describes it, with its name whole and in NFC. */
    private class Item(
        val id: String,
        val docId: String,
        val etag: String,
        val name: String,
        val type: String,
        val size: Long,
        val modifiedMs: Long,
    ) {
        val folder: Boolean get() = type == "FOLDER" || type == "APP_CONTAINER" || type == "APP_LIBRARY"

        /** Whether this session may rename, move or delete it: plain files and folders only. */
        val changeable: Boolean get() = type == "FILE" || type == "FOLDER"
    }

    private class Listing(val items: List<Item>, val at: Long)

    /** Folder listings by drivewsid, kept [LISTING_TTL_MS] and dropped by every change made here. */
    private val listings = HashMap<String, Listing>()

    /** As in [MegaFs]: the path an upload just failed at, which the store's clean-up must not touch. */
    private var failedUpload: List<String>? = null

    // ---- Session ---------------------------------------------------------------------

    /**
     * Signed in, by the stored cookies if they still work and by password and
     * trust token if not. Only a sign-in that was really needed is saved:
     * every save changes the location, which closes every open session.
     */
    private fun services(): ICloudSession.Services {
        services?.let { return it }
        if (!config.configured) throw IOException("iCloud is not signed in")
        val kept = session.validate()
        val found = kept ?: run {
            when (session.signIn(config.password)) {
                ICloudSession.SignIn.DONE -> Unit
                // The trust token has run out: only a person can type the code.
                ICloudSession.SignIn.NEEDS_CODE -> throw BackupSinkException(SinkError.PERMISSION_LOST)
                ICloudSession.SignIn.REFUSED -> throw BackupSinkException(SinkError.PERMISSION_LOST)
            }
            session.accountLogin() ?: throw BackupSinkException(SinkError.PERMISSION_LOST)
        }
        if (found.needsWebAccessApproval && !session.requestWebAccess(waitMs = 0L)) {
            throw BackupSinkException(SinkError.PERMISSION_LOST)
        }
        if (kept == null || (session.trustToken.isNotEmpty() && session.trustToken != config.trustToken)) {
            val cookies = session.cookieString()
            val trust = session.trustToken
            AutoBackupRunner.storeLocation(context, location.id, "iCloud session") {
                it.copy(icloud = it.icloud.copy(cookies = cookies, trustToken = trust.ifEmpty { it.icloud.trustToken }))
            }
        }
        return found.also { services = it }
    }

    /** One call with the session's cookies, signed in again once if iCloud says the session is gone. */
    private fun <T> call(request: (ICloudSession.Services) -> Request, read: (Response) -> T): T {
        val first = services()
        try {
            return session.send(request(first), "icloud files", read)
        } catch (expired: ICloudSession.Expired) {
            services = null
            session.dropSession()
        }
        val again = services()
        return try {
            session.send(request(again), "icloud files", read)
        } catch (expired: ICloudSession.Expired) {
            throw BackupSinkException(SinkError.PERMISSION_LOST, expired)
        }
    }

    private fun json(response: Response): JsonElement =
        runCatching { ICloudSession.json.parseToJsonElement(response.body?.string().orEmpty()) }.getOrNull()
            ?: throw IOException("iCloud sent something that is not JSON")

    // ---- Folders ---------------------------------------------------------------------

    private fun children(folderId: String, fresh: Boolean = false): List<Item> {
        val now = System.currentTimeMillis()
        if (!fresh) listings[folderId]?.takeIf { now - it.at < LISTING_TTL_MS }?.let { return it.items }
        val body = JsonArray(
            listOf(
                buildJsonObject {
                    put("drivewsid", JsonPrimitive(folderId))
                    put("partialData", JsonPrimitive(false))
                    put("includeHierarchy", JsonPrimitive(false))
                },
            ),
        )
        val folder = call({ session.post("${it.drive}/retrieveItemDetailsInFolders", body) }, ::json)
            .jsonArray.firstOrNull()?.jsonObject ?: throw IOException("iCloud sent no folder")
        folder.str("status")?.takeIf { it != "OK" }?.let { throw IOException("iCloud folder: $it") }
        // No list at all is only an empty folder when iCloud says it holds nothing:
        // a folder that merely looked empty could be deleted with what is in it.
        val raw = folder["items"] as? JsonArray
            ?: if ((folder.int("numberOfItems") ?: 0) == 0) JsonArray(emptyList()) else throw IOException("iCloud sent no items")
        val items = raw.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.str("drivewsid") ?: return@mapNotNull null
            val name = item.str("name").orEmpty()
            val extension = item.str("extension").orEmpty()
            Item(
                id = id,
                docId = item.str("docwsid").orEmpty(),
                etag = item.str("etag").orEmpty(),
                // iCloud keeps the extension apart and the name in NFD.
                name = nfc(if (extension.isEmpty()) name else "$name.$extension"),
                type = item.str("type").orEmpty(),
                size = (item["size"] as? JsonPrimitive)?.longOrNull ?: -1L,
                modifiedMs = item.str("dateModified")?.let(S3Listing::parseIso8601) ?: 0L,
            )
        }.filter { it.name.isNotEmpty() }
        listings[folderId] = Listing(items, now)
        return items
    }

    /** The one item in [items] called [name], exactly or else ignoring case; null when none, a throw when two. */
    private fun match(items: List<Item>, name: String): Item? {
        val wanted = nfc(name)
        val exact = items.filter { it.name == wanted }
        if (exact.size > 1) throw IOException("more than one item is called $name there")
        exact.firstOrNull()?.let { return it }
        val loose = items.filter { it.name.equals(wanted, ignoreCase = true) }
        if (loose.size > 1) throw IOException("more than one item is called $name there")
        return loose.firstOrNull()
    }

    /** The item at [path], the Drive's top for the empty path; null when nothing is there. */
    private fun resolve(path: List<String>, freshLast: Boolean = false): Item? {
        var at = ROOT
        for ((i, name) in path.withIndex()) {
            if (!at.folder) return null
            at = match(children(at.id, fresh = freshLast && i == path.lastIndex), name) ?: return null
        }
        return at
    }

    private fun folderAt(path: List<String>): Item =
        resolve(path)?.takeIf { it.folder } ?: throw IOException("the folder is not there")

    /** The item at [path] with its etag as iCloud has it now, which every change must send. */
    private fun current(path: List<String>): Pair<Item, Item>? {
        if (path.isEmpty()) return null
        val parent = resolve(path.dropLast(1))?.takeIf { it.folder } ?: return null
        val item = match(children(parent.id, fresh = true), path.last()) ?: return null
        return parent to item
    }

    private fun entryOf(item: Item): RemoteEntry =
        RemoteEntry(item.name, item.folder, if (item.folder) -1L else item.size, item.modifiedMs)

    // ---- RemoteFs ----------------------------------------------------------------------

    override fun list(dir: List<String>): List<RemoteEntry> {
        failedUpload = null
        return children(folderAt(dir).id).map(::entryOf)
    }

    override fun stat(path: List<String>): RemoteEntry? {
        failedUpload = null
        if (path.isEmpty()) return RemoteEntry("", true)
        return resolve(path)?.let(::entryOf)
    }

    override fun download(path: List<String>, to: OutputStream) {
        failedUpload = null
        val item = resolve(path)?.takeIf { !it.folder } ?: throw IOException("the file is not there")
        val zone = zoneOf(item.id)
        val document = URLEncoder.encode(item.id.substringAfterLast("::"), "UTF-8")
        val token = call({ session.request("${it.docs}/ws/$zone/download/by_id?document_id=$document").get().build() }, ::json).jsonObject
        val url = (token["data_token"] as? JsonObject)?.str("url")
            ?: throw IOException(if (token["package_token"] != null) "not supported here" else "iCloud gave no download address")
        call({ session.request(url).get().build() }) { response ->
            val body = response.body ?: throw IOException("iCloud download: no body")
            body.byteStream().use { it.copyTo(to, BUFFER) }
        }
    }

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        failedUpload = null
        try {
            send(folderAt(dir), name, from, onSent)
        } catch (failure: Throwable) {
            failedUpload = dir + name
            throw failure
        }
    }

    /**
     * The sink's three steps (ask for an upload address, POST the bytes, add
     * the document to the folder), with the bytes streamed from [from], then
     * the check that the document took the name it was given.
     */
    private fun send(parent: Item, name: String, from: File, onSent: (Long) -> Unit) {
        val zone = zoneOf(parent.id)
        val mime = FilePlaces.mimeOf(name) ?: OCTET
        val start = call({
            session.post(
                "${it.docs}/ws/$zone/upload/web",
                buildJsonObject {
                    put("filename", JsonPrimitive(name))
                    put("type", JsonPrimitive("FILE"))
                    put("size", JsonPrimitive(from.length().toString()))
                    put("content_type", JsonPrimitive(mime))
                },
            )
        }, ::json).jsonArray.firstOrNull()?.jsonObject ?: throw IOException("iCloud gave no upload address")
        val target = start.str("url") ?: throw IOException("iCloud gave no upload address")
        val document = start.str("document_id") ?: throw IOException("iCloud gave no document id")

        val sent = call({ session.request(target).post(FileBody(from, mime.toMediaTypeOrNull(), onSent)).build() }, ::json)
            .jsonObject["singleFile"] as? JsonObject ?: throw IOException("iCloud did not take the bytes")

        val now = System.currentTimeMillis()
        val update = buildJsonObject {
            put("allow_conflict", JsonPrimitive(true))
            put("btime", JsonPrimitive(now))
            put("command", JsonPrimitive("add_file"))
            put("create_short_guid", JsonPrimitive(true))
            put(
                "data",
                buildJsonObject {
                    sent["receipt"]?.let { put("receipt", it) }
                    sent["referenceChecksum"]?.let { put("reference_signature", it) }
                    sent["fileChecksum"]?.let { put("signature", it) }
                    sent["size"]?.let { put("size", it) }
                    sent["wrappingKey"]?.let { put("wrapping_key", it) }
                },
            )
            put("document_id", JsonPrimitive(document))
            put(
                "file_flags",
                buildJsonObject {
                    put("is_executable", JsonPrimitive(false))
                    put("is_hidden", JsonPrimitive(false))
                    put("is_writable", JsonPrimitive(true))
                },
            )
            put("mtime", JsonPrimitive(now))
            put(
                "path",
                buildJsonObject {
                    put("path", JsonPrimitive(name))
                    put("starting_document_id", JsonPrimitive(parent.id.substringAfterLast("::")))
                },
            )
        }
        call({ session.post("${it.docs}/ws/$zone/update/documents", update) }) { }
        listings.remove(parent.id)

        // Found by its document id, so a file someone else gave the name meanwhile is never mistaken for it.
        repeat(LOOKUPS) { attempt ->
            val made = children(parent.id, fresh = true).firstOrNull { it.docId == document || it.id.substringAfterLast("::") == document }
            if (made != null) {
                if (made.name == nfc(name)) return
                // iCloud kept both and called ours something else: it is ours, so it goes again.
                runCatching { trash(made) }
                listings.remove(parent.id)
                throw IOException("iCloud stored the upload as ${made.name}")
            }
            if (attempt < LOOKUPS - 1) Thread.sleep(LOOKUP_WAIT_MS)
        }
        throw IOException("the upload does not show in its folder")
    }

    override fun mkdir(dir: List<String>, name: String) {
        failedUpload = null
        val parent = folderAt(dir)
        val body = buildJsonObject {
            put("destinationDrivewsId", JsonPrimitive(parent.id))
            put(
                "folders",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("clientId", JsonPrimitive("FOLDER::UNKNOWN_ZONE::TempId-${UUID.randomUUID()}"))
                            put("name", JsonPrimitive(name))
                        },
                    ),
                ),
            )
        }
        val answer = call({ session.post("${it.drive}/createFolders", body) }, ::json).jsonObject
        listings.remove(parent.id)
        val made = answer["folders"]?.jsonArray?.firstOrNull() as? JsonObject ?: throw IOException("iCloud made no folder")
        made.str("status")?.takeIf { it != "OK" }?.let { throw IOException("iCloud folder: $it") }
        made.str("name")?.let { given ->
            if (nfc(given) == nfc(name)) return
            // iCloud kept the other folder and named this one apart: it is ours and empty, so it goes again, as an upload's does.
            val id = made.str("drivewsid")
            val etag = made.str("etag")
            if (id != null && etag != null) runCatching { trash(Item(id, "", etag, given, "FOLDER", -1L, 0L)) }
            throw IOException("iCloud named the folder $given")
        }
    }

    override fun delete(path: List<String>, isDirectory: Boolean) {
        if (failedUpload == path) {
            failedUpload = null
            return
        }
        failedUpload = null
        val (parent, item) = current(path) ?: return
        if (item.folder != isDirectory) throw IOException("not the kind of item that was asked for")
        if (!item.changeable) throw IOException("not supported here")
        // Trashing a folder takes what is in it along: only an empty one goes.
        if (item.folder && children(item.id, fresh = true).isNotEmpty()) throw IOException("the folder is not empty")
        try {
            trash(item)
        } catch (gone: BackupSinkException) {
            if (gone.reason != SinkError.TARGET_MISSING) throw gone
        }
        listings.remove(parent.id)
        listings.remove(item.id)
    }

    /** To Recently Deleted, as the sink and rclone do it. */
    private fun trash(item: Item) {
        val body = buildJsonObject {
            put(
                "items",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("drivewsid", JsonPrimitive(item.id))
                            put("etag", JsonPrimitive(item.etag))
                            put("clientId", JsonPrimitive(item.id))
                        },
                    ),
                ),
            )
        }
        checkItems(call({ session.post("${it.drive}/moveItemsToTrash", body) }, ::json), "delete")
    }

    override fun rename(path: List<String>, newName: String) {
        failedUpload = null
        val (parent, item) = current(path) ?: throw IOException("nothing to rename")
        if (!item.changeable) throw IOException("not supported here")
        val siblings = children(parent.id)
        if (siblings.any { it.id != item.id && it.name.equals(nfc(newName), ignoreCase = true) }) throw IOException("$newName is taken")
        // The whole name, extension and all, as rclone and pyicloud send it.
        val body = buildJsonObject {
            put(
                "items",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("drivewsid", JsonPrimitive(item.id))
                            put("etag", JsonPrimitive(item.etag))
                            put("name", JsonPrimitive(newName))
                        },
                    ),
                ),
            )
        }
        val answer = call({ session.post("${it.drive}/renameItems", body) }, ::json)
        listings.remove(parent.id)
        checkItems(answer, "rename")
    }

    override fun move(path: List<String>, toDir: List<String>): Boolean {
        failedUpload = null
        val (parent, item) = current(path) ?: throw IOException("nothing to move")
        if (!item.changeable) throw IOException("not supported here")
        val destination = folderAt(toDir)
        if (destination.id == item.id) throw IOException("a folder cannot go inside itself")
        val body = buildJsonObject {
            put("destinationDrivewsId", JsonPrimitive(destination.id))
            put(
                "items",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("drivewsid", JsonPrimitive(item.id))
                            put("etag", JsonPrimitive(item.etag))
                            put("clientId", JsonPrimitive(item.id))
                        },
                    ),
                ),
            )
        }
        val answer = call({ session.post("${it.drive}/moveItems", body) }, ::json)
        listings.remove(parent.id)
        listings.remove(destination.id)
        checkItems(answer, "move")
        return true
    }

    override val atomicUpload: Boolean get() = true

    override val caseInsensitive: Boolean get() = true

    // ---- Helpers -----------------------------------------------------------------------

    /**
     * Throws when iCloud says an item of the change was refused. An answer
     * without the list says nothing either way: the store looks for the
     * result itself next.
     */
    private fun checkItems(answer: JsonElement, what: String) {
        val items = (answer as? JsonObject)?.get("items") as? JsonArray ?: return
        for (element in items) {
            val status = (element as? JsonObject)?.str("status") ?: continue
            if (status != "OK") throw IOException("iCloud $what: $status")
        }
    }

    private fun zoneOf(id: String): String = id.split("::").getOrNull(1)?.takeIf { it.isNotEmpty() } ?: DEFAULT_ZONE

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    /** A file as a request body, counted as it goes so the panel can show progress. */
    private class FileBody(private val file: File, private val type: MediaType?, private val onSent: (Long) -> Unit) : RequestBody() {
        override fun contentType(): MediaType? = type

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            file.source().use { source ->
                val buffer = okio.Buffer()
                while (true) {
                    val n = source.read(buffer, BUFFER.toLong())
                    if (n < 0) break
                    sink.write(buffer, n)
                    onSent(n)
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_ZONE = "com.apple.CloudDocs"
        private const val ROOT_ID = "FOLDER::com.apple.CloudDocs::root"
        private const val OCTET = "application/octet-stream"
        private const val BUFFER = 64 * 1024
        private const val LISTING_TTL_MS = 10_000L
        private const val LOOKUPS = 3
        private const val LOOKUP_WAIT_MS = 1_000L

        private val ROOT = Item(ROOT_ID, "root", "", "", "FOLDER", -1L, 0L)

        private fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
    }
}

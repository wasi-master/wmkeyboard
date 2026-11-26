package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.FilePlaces
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.sink.proton.ProtonApi
import com.wasimaster.wmkeyboard.core.settings.sink.proton.ProtonError
import com.wasimaster.wmkeyboard.core.settings.sink.proton.ProtonPgp
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The account's Proton Drive (the main volume's "My files"), end-to-end
 * encrypted the way [com.wasimaster.wmkeyboard.core.settings.sink.proton.ProtonDriveSink]
 * does it, with the session and salted key password the backup's sign-in
 * saved. Rotated tokens are saved back to the location as the sink saves them.
 *
 * The key chain is the sink's: user keys, the address key, the share key,
 * then one node key per folder, each unlocking its children's names and
 * keys. Unlocked folder keys are kept for the session.
 *
 * - **Upload** makes a file draft, encrypts and sends the file in 4 MiB
 *   blocks read from disk (a few blocks in memory at a time), and commits
 *   the revision with its manifest; a failure deletes the draft. Proton
 *   refuses a second file of one name in a folder (the name hash is unique),
 *   and a draft is not listed, so the file appears whole or not at all
 *   ([atomicUpload]).
 * - **Download** checks every block's hash, that no block is missing, and
 *   the size the file's own attributes claim.
 * - **Rename** encrypts the new name to the parent folder's key and hashes
 *   it with the parent's hash key; both are checked here before Proton sees
 *   them, and the old name's hash must match first, which proves the hash
 *   key is the right one.
 * - **Move** is not done on the server: it means re-encrypting the node's
 *   passphrase to the new parent's key, and a wrong field there leaves a
 *   file no key opens. [move] answers false and the store copies and
 *   deletes, which only removes the original once the copy is whole.
 * - **Delete** is trash, then delete from the trash, as the sink does it. A
 *   folder goes only when a fresh look finds nothing in it at all, items
 *   this session cannot read and items waiting in the trash included.
 */
class ProtonFs(private val context: Context, private val location: BackupLocation) : RemoteFs {

    private val config = location.proton

    private val api by lazy {
        if (!config.configured) throw IOException("Proton Drive is not signed in")
        ProtonApi(config.uid, config.accessToken, config.refreshToken) { access, refresh ->
            AutoBackupRunner.storeLocation(context, location.id, "Proton tokens") {
                it.copy(proton = it.proton.copy(accessToken = access, refreshToken = refresh))
            }
        }
    }

    /** What every call needs once signed in and unlocked. */
    private class Drive(
        val volume: String,
        val address: ProtonPgp.Key,
        val addressId: String,
        val email: String,
        val root: Folder,
    )

    /** A folder's link id, its unlocked node key and its hash key. */
    private class Folder(val id: String, val key: ProtonPgp.Key, val hashKey: ByteArray)

    /** One link in a folder. [name] is null when it does not decrypt; [hash] is its name hash. */
    private class Child(
        val id: String,
        val name: String?,
        val type: Int,
        val state: Int,
        val hash: String?,
        val details: JsonObject,
    ) {
        val link: JsonObject? get() = details["Link"] as? JsonObject

        val visible: Boolean get() = state == STATE_ACTIVE && name != null && (type == TYPE_FILE || type == TYPE_FOLDER)
    }

    private class Listing(val children: List<Child>, val at: Long)

    private var drive: Drive? = null

    /** Folder listings by link id, kept [LISTING_TTL_MS] and dropped by every change made here. */
    private val listings = HashMap<String, Listing>()

    /** Unlocked folders by link id: a node's keys never change. */
    private val folders = HashMap<String, Folder>()

    /** Unlocked file node keys by link id. */
    private val nodeKeys = HashMap<String, ProtonPgp.Key>()

    /** Sizes from the files' own attributes, by `link:revision`. */
    private val sizes = HashMap<String, Long>()

    /** As in [MegaFs]: the path an upload just failed at, which the store's clean-up must not touch. */
    private var failedUpload: List<String>? = null

    // ---- Session -----------------------------------------------------------------------

    /** Signed in and unlocked down to the root folder, as the sink's `drive()` does it. */
    private fun drive(): Drive {
        drive?.let { return it }
        val pass = config.saltedKeyPass.toByteArray(Charsets.US_ASCII)
        val userKeys = api.get("core/v4/users")["User"]?.jsonObject?.get("Keys")?.jsonArray.orEmpty()
            .mapNotNull { k -> k.jsonObject.str("PrivateKey")?.let { ProtonPgp.unlock(it, pass) } }
        if (userKeys.isEmpty()) throw IOException("no Proton user key unlocks")

        val myFiles = api.get("drive/v2/shares/my-files")
        val volume = (myFiles["Volume"] as? JsonObject)?.str("VolumeID") ?: throw IOException("Proton sent no volume")
        val share = myFiles["Share"] as? JsonObject ?: throw IOException("Proton sent no share")
        val addressId = share.str("AddressID") ?: throw IOException("Proton sent no address")

        val addresses = api.get("core/v4/addresses")["Addresses"]?.jsonArray.orEmpty().map { it.jsonObject }
        val address = addresses.firstOrNull { it.str("ID") == addressId } ?: throw IOException("the share's address is not the account's")
        val addressKey = address["Keys"]?.jsonArray.orEmpty().map { it.jsonObject }
            .sortedByDescending { it.int("Primary") ?: 0 }
            .firstNotNullOfOrNull { key ->
                val armored = key.str("PrivateKey") ?: return@firstNotNullOfOrNull null
                // A key with a token is unlocked by the token, which a user key
                // decrypts; an older one by the key password itself.
                val secret = key.str("Token")?.let { runCatching { ProtonPgp.decryptArmored(it, userKeys) }.getOrNull() } ?: pass
                ProtonPgp.unlock(armored, secret)
            } ?: throw IOException("no Proton address key unlocks")

        val shareKey = unlockNode(share.str("Key"), share.str("Passphrase"), addressKey)
        val rootDetails = myFiles["Link"] as? JsonObject ?: throw IOException("Proton sent no root folder")
        val root = folderOf(rootDetails, shareKey)
        return Drive(volume, addressKey, addressId, address.str("Email") ?: share.str("CreatorEmail").orEmpty(), root)
            .also { drive = it }
    }

    private fun unlockNode(armoredKey: String?, armoredPassphrase: String?, parent: ProtonPgp.Key): ProtonPgp.Key {
        if (armoredKey == null || armoredPassphrase == null) throw IOException("a node has no key")
        val passphrase = runCatching { ProtonPgp.decryptArmored(armoredPassphrase, listOf(parent)) }.getOrNull()
            ?: throw IOException("a node's passphrase does not decrypt")
        return ProtonPgp.unlock(armoredKey, passphrase) ?: throw IOException("a node's key does not unlock")
    }

    /** A folder from its link details (`{Link, Folder}`) and its parent's key. */
    private fun folderOf(details: JsonObject, parent: ProtonPgp.Key): Folder {
        val link = details["Link"] as? JsonObject ?: throw IOException("a folder has no link")
        val id = link.str("LinkID") ?: throw IOException("a folder has no id")
        folders[id]?.let { return it }
        val key = unlockNode(link.str("NodeKey"), link.str("NodePassphrase"), parent)
        val hashKey = (details["Folder"] as? JsonObject)?.str("NodeHashKey")
            ?.let { runCatching { ProtonPgp.decryptArmored(it, listOf(key)) }.getOrNull() }
            ?: throw IOException("a folder's hash key does not decrypt")
        return Folder(id, key, hashKey).also { folders[id] = it }
    }

    private fun folderOf(child: Child, parent: Folder): Folder = folderOf(child.details, parent.key)

    private fun nodeKey(child: Child, parent: Folder): ProtonPgp.Key =
        nodeKeys[child.id] ?: unlockNode(child.link?.str("NodeKey"), child.link?.str("NodePassphrase"), parent.key)
            .also { nodeKeys[child.id] = it }

    // ---- Folders -----------------------------------------------------------------------

    /** Every link in [folder], whatever its state, names decrypted where they will. */
    private fun children(folder: Folder, fresh: Boolean = false): List<Child> {
        val now = System.currentTimeMillis()
        if (!fresh) listings[folder.id]?.takeIf { now - it.at < LISTING_TTL_MS }?.let { return it.children }
        val d = drive()
        val ids = ArrayList<String>()
        var anchor = ""
        while (true) {
            val page = api.get(
                "drive/v2/volumes/${d.volume}/folders/${folder.id}/children" + if (anchor.isEmpty()) "" else "?AnchorID=$anchor",
            )
            page["LinkIDs"]?.jsonArray.orEmpty().mapNotNullTo(ids) { (it as? JsonPrimitive)?.contentOrNull }
            val more = page["More"]?.jsonPrimitive?.let { it.booleanOrNull == true || it.intOrNull == 1 } == true
            anchor = page.str("AnchorID").orEmpty()
            if (!more || anchor.isEmpty()) break
        }
        val out = ArrayList<Child>()
        for (batch in ids.chunked(LINKS_PER_CALL)) {
            val body = buildJsonObject { put("LinkIDs", JsonArray(batch.map(::JsonPrimitive))) }
            val links = api.post("drive/v2/volumes/${d.volume}/links", body)["Links"]?.jsonArray.orEmpty()
            val answered = HashSet<String>()
            for (element in links) {
                val details = element as? JsonObject ?: throw IOException("Proton sent an item that is not one")
                val link = details["Link"] as? JsonObject ?: throw IOException("Proton sent an item with no link")
                val name = link.str("Name")?.let { runCatching { ProtonPgp.decryptArmored(it, listOf(folder.key)).decodeToString() }.getOrNull() }
                val id = link.str("LinkID") ?: throw IOException("Proton sent an item with no id")
                answered += id
                out += Child(
                    id = id,
                    name = name?.takeIf { it.isNotEmpty() },
                    type = link.int("Type") ?: -1,
                    state = link.int("State") ?: -1,
                    hash = link.str("NameHash") ?: link.str("Hash"),
                    details = details,
                )
            }
            // A link asked for and not described could be anything: it is kept,
            // unnamed and in no known state, so the folder never looks emptier than it is.
            for (id in batch) {
                if (id !in answered) out += Child(id, null, -1, -1, null, JsonObject(emptyMap()))
            }
        }
        listings[folder.id] = Listing(out, now)
        return out
    }

    /** The folder holding [path] and the link at it, or null when nothing is there. */
    private fun walk(path: List<String>): Pair<Folder, Child>? {
        var at = drive().root
        for ((i, name) in path.withIndex()) {
            val matches = children(at).filter { it.visible && it.name == name }
            if (matches.size > 1) throw IOException("more than one item is called $name there")
            val child = matches.firstOrNull() ?: return null
            if (i == path.lastIndex) return at to child
            if (child.type != TYPE_FOLDER) return null
            at = folderOf(child, at)
        }
        return null
    }

    private fun folderAt(dir: List<String>): Folder {
        if (dir.isEmpty()) return drive().root
        val (parent, child) = walk(dir)?.takeIf { it.second.type == TYPE_FOLDER } ?: throw IOException("the folder is not there")
        return folderOf(child, parent)
    }

    private fun activeRevision(child: Child): JsonObject? =
        (child.details["File"] as? JsonObject)?.get("ActiveRevision")?.takeIf { it !is JsonNull } as? JsonObject

    private fun revisionId(revision: JsonObject): String? = revision.str("RevisionID") ?: revision.str("ID")

    /** The size the file's own attributes give, -1 when they do not say or do not decrypt. */
    private fun sizeOf(child: Child, parent: Folder): Long {
        val revision = activeRevision(child) ?: return -1L
        val cacheKey = "${child.id}:${revisionId(revision)}"
        sizes[cacheKey]?.let { return it }
        val size = revision.str("XAttr")?.let { armored ->
            runCatching {
                val plain = ProtonPgp.decryptArmored(armored, listOf(nodeKey(child, parent))).decodeToString()
                json.parseToJsonElement(plain).jsonObject["Common"]?.jsonObject?.get("Size")?.jsonPrimitive?.longOrNull
            }.getOrNull()
        } ?: -1L
        sizes[cacheKey] = size
        return size
    }

    private fun entryOf(child: Child, parent: Folder): RemoteEntry {
        val folder = child.type == TYPE_FOLDER
        val revision = activeRevision(child)
        val seconds = (revision?.get("CreateTime") ?: child.link?.get("ModifyTime"))?.let { (it as? JsonPrimitive)?.longOrNull } ?: 0L
        return RemoteEntry(child.name.orEmpty(), folder, if (folder) -1L else sizeOf(child, parent), seconds * 1000L)
    }

    // ---- RemoteFs ----------------------------------------------------------------------

    override fun list(dir: List<String>): List<RemoteEntry> {
        failedUpload = null
        val folder = folderAt(dir)
        return children(folder).filter { it.visible }.map { entryOf(it, folder) }
    }

    override fun stat(path: List<String>): RemoteEntry? {
        failedUpload = null
        if (path.isEmpty()) return RemoteEntry("", true)
        val (parent, child) = walk(path) ?: return null
        return entryOf(child, parent)
    }

    override fun download(path: List<String>, to: OutputStream) {
        failedUpload = null
        val d = drive()
        val (parent, child) = walk(path)?.takeIf { it.second.type == TYPE_FILE } ?: throw IOException("the file is not there")
        val key = nodeKey(child, parent)
        val packet = (child.details["File"] as? JsonObject)?.str("ContentKeyPacket")?.let { Base64.decode(it) }
            ?: throw IOException("the file has no content key")
        val revision = activeRevision(child)?.let(::revisionId) ?: throw IOException("the file has no revision")
        val claimed = sizeOf(child, parent)

        var written = 0L
        var expected = 1
        while (true) {
            val blocks = api.get("drive/v2/volumes/${d.volume}/files/${child.id}/revisions/$revision?PageSize=$BLOCK_PAGE&FromBlockIndex=$expected")["Revision"]
                ?.jsonObject?.get("Blocks")?.jsonArray.orEmpty().map { it.jsonObject }.sortedBy { it.int("Index") ?: 0 }
            for (block in blocks) {
                // A gap would make a file that is short in the middle and still decrypts.
                if (block.int("Index") != expected) throw IOException("block $expected of the file is missing")
                val cipher = api.downloadBlock(block.str("BareURL") ?: throw IOException("a block has no address"), block.str("Token").orEmpty())
                val hash = Base64.encode(MessageDigest.getInstance("SHA-256").digest(cipher))
                if (block.str("Hash")?.let { it != hash } == true) throw IOException("block $expected does not match its hash")
                val plain = ProtonPgp.decrypt(packet + cipher, listOf(key))
                to.write(plain)
                written += plain.size
                expected++
            }
            // Asked again until a page comes back empty: a server that pages
            // shorter than asked must not end the file early.
            if (blocks.isEmpty()) break
        }
        if (claimed >= 0 && written != claimed) throw IOException("the file is $written bytes, not the $claimed it says")
    }

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        failedUpload = null
        try {
            val d = drive()
            val folder = folderAt(dir)
            val draft = newFile(d, folder, name, FilePlaces.mimeOf(name) ?: OCTET)
            try {
                send(d, draft, from, onSent)
            } catch (failure: Throwable) {
                runCatching {
                    api.post(
                        "drive/v2/volumes/${d.volume}/delete_multiple",
                        buildJsonObject { put("LinkIDs", JsonArray(listOf(JsonPrimitive(draft.linkId)))) },
                    )
                }
                throw failure
            } finally {
                listings.remove(folder.id)
            }
        } catch (failure: Throwable) {
            failedUpload = dir + name
            throw failure
        }
    }

    /** A draft being uploaded: where it is, and the keys its content goes under. */
    private class Draft(val linkId: String, val revisionId: String, val nodeKey: ProtonPgp.Key, val sessionKey: ByteArray)

    /** A new file's draft, keys and all, as the sink makes one. Proton refuses it when the name is taken. */
    private fun newFile(d: Drive, folder: Folder, name: String, mimeType: String): Draft {
        val passphrase = ProtonPgp.newPassphrase()
        val key = ProtonPgp.unlock(ProtonPgp.generateKey(passphrase), passphrase.toByteArray())
            ?: throw IOException("a new key does not unlock")
        val sessionKey = ProtonPgp.newSessionKey()
        val body = buildJsonObject {
            put("ParentLinkID", JsonPrimitive(folder.id))
            put("Name", JsonPrimitive(ProtonPgp.encrypt(folder.key, name.toByteArray(), signer = d.address)))
            put("Hash", JsonPrimitive(ProtonPgp.nameHash(folder.hashKey, name)))
            put("MIMEType", JsonPrimitive(mimeType))
            put("NodeKey", JsonPrimitive(key.armored))
            put("NodePassphrase", JsonPrimitive(ProtonPgp.encrypt(folder.key, passphrase.toByteArray())))
            put("NodePassphraseSignature", JsonPrimitive(ProtonPgp.signArmored(d.address, passphrase.toByteArray())))
            put("ContentKeyPacket", JsonPrimitive(Base64.encode(ProtonPgp.keyPacket(key, sessionKey))))
            put("ContentKeyPacketSignature", JsonPrimitive(ProtonPgp.signArmored(key, sessionKey)))
            put("SignatureAddress", JsonPrimitive(d.email))
        }
        val file = api.post("drive/v2/volumes/${d.volume}/files", body)["File"] as? JsonObject ?: throw IOException("Proton made no file")
        return Draft(
            linkId = file.str("ID") ?: throw IOException("Proton made no file"),
            revisionId = file.str("RevisionID") ?: throw IOException("Proton made no revision"),
            nodeKey = key,
            sessionKey = sessionKey,
        )
    }

    /** One encrypted block waiting for its upload address. */
    private class Block(val index: Int, val cipher: ByteArray, val signature: String, val token: ByteArray, val plainSize: Int)

    /**
     * Encrypts and uploads [from] block by block, [BLOCKS_PER_REQUEST] at a
     * time, then commits the revision with its manifest and attributes, as
     * the sink's upload does with a whole file in memory.
     */
    private fun send(d: Drive, draft: Draft, from: File, onSent: (Long) -> Unit) {
        val verification = api.get("drive/v2/volumes/${d.volume}/links/${draft.linkId}/revisions/${draft.revisionId}/verification")
        val code = verification.str("VerificationCode")?.let { Base64.decode(it) } ?: throw IOException("Proton sent no verification code")

        val manifest = ByteArrayOutputStream()
        val sha1 = MessageDigest.getInstance("SHA-1")
        val blockSizes = ArrayList<Int>()
        var total = 0L
        var index = 0
        from.inputStream().use { input ->
            while (true) {
                val batch = ArrayList<Block>()
                while (batch.size < BLOCKS_PER_REQUEST) {
                    val plain = readBlock(input) ?: break
                    index++
                    sha1.update(plain)
                    blockSizes += plain.size
                    total += plain.size
                    val cipher = ProtonPgp.encryptWithSessionKey(draft.sessionKey, plain)
                    val signature = ProtonPgp.encrypt(draft.nodeKey, ProtonPgp.sign(d.address, plain))
                    // The server's check that the block was made with the key it
                    // holds: the code XORed with the ciphertext's first bytes.
                    val token = ByteArray(code.size) { (code[it].toInt() xor (cipher.getOrNull(it)?.toInt() ?: 0)).toByte() }
                    batch += Block(index, cipher, signature, token, plain.size)
                }
                if (batch.isEmpty()) break
                val request = buildJsonObject {
                    put("AddressID", JsonPrimitive(d.addressId))
                    put("VolumeID", JsonPrimitive(d.volume))
                    put("LinkID", JsonPrimitive(draft.linkId))
                    put("RevisionID", JsonPrimitive(draft.revisionId))
                    put(
                        "BlockList",
                        JsonArray(
                            batch.map { b ->
                                buildJsonObject {
                                    put("Index", JsonPrimitive(b.index))
                                    put("EncSignature", JsonPrimitive(b.signature))
                                    put("Verifier", buildJsonObject { put("Token", JsonPrimitive(Base64.encode(b.token))) })
                                }
                            },
                        ),
                    )
                    put("ThumbnailList", JsonArray(emptyList()))
                }
                val links = api.post("drive/blocks", request)["UploadLinks"]?.jsonArray.orEmpty().map { it.jsonObject }
                for ((position, b) in batch.withIndex()) {
                    val link = links.firstOrNull { it.int("Index") == b.index } ?: links.getOrNull(position)
                        ?: throw IOException("Proton gave no address for block ${b.index}")
                    api.uploadBlock(link.str("BareURL") ?: throw IOException("Proton gave no address for block ${b.index}"), link.str("Token").orEmpty(), b.cipher)
                    manifest.write(MessageDigest.getInstance("SHA-256").digest(b.cipher))
                    onSent(b.plainSize.toLong())
                }
            }
        }
        if (total != from.length()) throw IOException("the file changed while it was sent")

        val xattr = buildJsonObject {
            put(
                "Common",
                buildJsonObject {
                    put("ModificationTime", JsonPrimitive(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())))
                    put("Size", JsonPrimitive(total))
                    put("BlockSizes", JsonArray(blockSizes.map { JsonPrimitive(it) }))
                    put("Digests", buildJsonObject { put("SHA1", JsonPrimitive(sha1.digest().joinToString("") { "%02x".format(it) })) })
                },
            )
        }
        val commit = buildJsonObject {
            put("ManifestSignature", JsonPrimitive(ProtonPgp.signArmored(d.address, manifest.toByteArray())))
            put("SignatureAddress", JsonPrimitive(d.email))
            put("XAttr", JsonPrimitive(ProtonPgp.encrypt(draft.nodeKey, xattr.toString().toByteArray(), signer = d.address, compress = true)))
            put("ChecksumVerified", JsonPrimitive(false))
            put("Photo", JsonNull)
        }
        api.put("drive/v2/volumes/${d.volume}/files/${draft.linkId}/revisions/${draft.revisionId}", commit)
    }

    /** Up to [BLOCK_SIZE] bytes, fewer only at the end; null once nothing is left. */
    private fun readBlock(input: InputStream): ByteArray? {
        val buffer = ByteArray(BLOCK_SIZE)
        var filled = 0
        while (filled < BLOCK_SIZE) {
            val n = input.read(buffer, filled, BLOCK_SIZE - filled)
            if (n < 0) break
            filled += n
        }
        return when (filled) {
            0 -> null
            BLOCK_SIZE -> buffer
            else -> buffer.copyOf(filled)
        }
    }

    override fun mkdir(dir: List<String>, name: String) {
        failedUpload = null
        val d = drive()
        val parent = folderAt(dir)
        val passphrase = ProtonPgp.newPassphrase()
        val key = ProtonPgp.unlock(ProtonPgp.generateKey(passphrase), passphrase.toByteArray())
            ?: throw IOException("a new key does not unlock")
        val hashKey = ProtonPgp.newPassphrase().toByteArray(Charsets.US_ASCII)
        val body = buildJsonObject {
            put("ParentLinkID", JsonPrimitive(parent.id))
            put("NodeKey", JsonPrimitive(key.armored))
            put("NodeHashKey", JsonPrimitive(ProtonPgp.encrypt(key, hashKey, signer = key)))
            put("NodePassphrase", JsonPrimitive(ProtonPgp.encrypt(parent.key, passphrase.toByteArray())))
            put("NodePassphraseSignature", JsonPrimitive(ProtonPgp.signArmored(d.address, passphrase.toByteArray())))
            put("SignatureEmail", JsonPrimitive(d.email))
            put("Name", JsonPrimitive(ProtonPgp.encrypt(parent.key, name.toByteArray(), signer = d.address)))
            put("Hash", JsonPrimitive(ProtonPgp.nameHash(parent.hashKey, name)))
        }
        try {
            val id = (api.post("drive/v2/volumes/${d.volume}/folders", body)["Folder"] as? JsonObject)?.str("ID")
                ?: throw IOException("Proton made no folder")
            folders[id] = Folder(id, key, hashKey)
        } finally {
            listings.remove(parent.id)
        }
    }

    override fun delete(path: List<String>, isDirectory: Boolean) {
        if (failedUpload == path) {
            failedUpload = null
            return
        }
        failedUpload = null
        val d = drive()
        val (parent, child) = walk(path) ?: return
        if ((child.type == TYPE_FOLDER) != isDirectory) throw IOException("not the kind of item that was asked for")
        if (child.type == TYPE_FOLDER) {
            // Deleting a folder deletes everything under it, the trash's share
            // of it too: only one with nothing left in any state goes.
            val inside = children(folderOf(child, parent), fresh = true)
            if (inside.any { it.state != STATE_DELETED }) throw IOException("the folder is not empty")
        }
        val ids = buildJsonObject { put("LinkIDs", JsonArray(listOf(JsonPrimitive(child.id)))) }
        try {
            // To the trash, then out of it: Proton counts the trash against the quota.
            checkResponses(api.post("drive/v2/volumes/${d.volume}/trash_multiple", ids), "trash")
            checkResponses(api.post("drive/v2/volumes/${d.volume}/trash/delete_multiple", ids), "delete")
        } catch (gone: ProtonError) {
            if (gone.reason != SinkError.TARGET_MISSING) throw gone
        } finally {
            listings.remove(parent.id)
        }
        folders.remove(child.id)
        nodeKeys.remove(child.id)
    }

    override fun rename(path: List<String>, newName: String) {
        failedUpload = null
        val d = drive()
        val (parent, found) = walk(path) ?: throw IOException("nothing to rename")
        val fresh = children(parent, fresh = true)
        val child = fresh.firstOrNull { it.id == found.id && it.visible } ?: throw IOException("nothing to rename")
        val oldName = child.name ?: throw IOException("nothing to rename")
        val originalHash = child.hash ?: throw IOException("not supported here")
        // The old name hashing to what Proton holds proves this is the folder's hash key.
        if (ProtonPgp.nameHash(parent.hashKey, oldName) != originalHash) throw IOException("not supported here")
        val newHash = ProtonPgp.nameHash(parent.hashKey, newName)
        if (fresh.any { it.id != child.id && (it.hash == newHash || it.name == newName) }) throw IOException("$newName is taken")
        val armored = ProtonPgp.encrypt(parent.key, newName.toByteArray(), signer = d.address)
        // A name no client can read would hide the file: it must open before Proton keeps it.
        val readBack = runCatching { ProtonPgp.decryptArmored(armored, listOf(parent.key)).decodeToString() }.getOrNull()
        if (readBack != newName) throw IOException("the new name does not read back")
        try {
            api.put(
                "drive/v2/volumes/${d.volume}/links/${child.id}/rename",
                buildJsonObject {
                    put("Name", JsonPrimitive(armored))
                    put("Hash", JsonPrimitive(newHash))
                    put("OriginalHash", JsonPrimitive(originalHash))
                    put("NameSignatureEmail", JsonPrimitive(d.email))
                    put("SignatureAddress", JsonPrimitive(d.email))
                },
            )
        } finally {
            listings.remove(parent.id)
        }
    }

    /** Never on the server here: see the class notes. The store copies, then deletes. */
    override fun move(path: List<String>, toDir: List<String>): Boolean = false

    override val atomicUpload: Boolean get() = true

    // ---- Helpers -----------------------------------------------------------------------

    /** Throws when any link in a batch answer was refused; one already gone counts as done. */
    private fun checkResponses(answer: JsonObject, what: String) {
        for (element in answer["Responses"]?.jsonArray.orEmpty()) {
            val code = ((element as? JsonObject)?.get("Response") as? JsonObject)?.int("Code") ?: continue
            if (code != CODE_OK && code != CODE_MISSING) throw IOException("Proton $what: code $code")
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    companion object {
        private const val BLOCK_SIZE = 4 * 1024 * 1024

        /** Blocks asked for and held at once: 8 MiB of ciphertext in memory, not the file. */
        private const val BLOCKS_PER_REQUEST = 2
        private const val BLOCK_PAGE = 100
        private const val LINKS_PER_CALL = 100
        private const val LISTING_TTL_MS = 10_000L
        private const val STATE_ACTIVE = 1
        private const val STATE_DELETED = 3
        private const val TYPE_FOLDER = 1
        private const val TYPE_FILE = 2
        private const val CODE_OK = 1000
        private const val CODE_MISSING = 2501
        private const val OCTET = "application/octet-stream"

        private val json = Json { ignoreUnknownKeys = true }
    }
}

package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.sink.BackupHttp
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.MegaApi
import com.wasimaster.wmkeyboard.core.settings.sink.MegaCrypto
import com.wasimaster.wmkeyboard.core.settings.sink.MegaError
import com.wasimaster.wmkeyboard.core.settings.sink.MegaSink
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The account's MEGA cloud drive, from its top, with the session id and
 * master key the backup's sign-in saved. Speaks the same API as [MegaSink]
 * (ported from go-mega and megatools), with everything encrypted and
 * decrypted on the phone.
 *
 * - **The tree** comes whole from one `f` call and is decrypted here: every
 *   node's key unwrapped with the master key (or, for something a
 *   collaborator put in a folder this account shares, with that share's key
 *   from the answer's `ok` list), and its name from its attributes. It is
 *   kept between calls, changed in place by this session's own edits, and
 *   fetched again once [TREE_TTL_MS] old.
 * - **Nothing is ever replaced**: MEGA has no overwrite at all. A `p` call
 *   always makes a new node, even beside one of the same name, and a rename
 *   only rewrites the node's own attributes. So [atomicUpload] is true: the
 *   node only exists once the last chunk is in and `p` has run.
 * - **Two items of one name** are possible in MEGA. Listing shows both, each
 *   with its handle as [RemoteEntry.key], and the Files tool gives each its
 *   own path from that ([pick]). A path to one of them goes to that handle
 *   only; with no pick it is refused rather than guessed.
 * - **A node whose key does not open** (shared to this account through a
 *   key it does not hold, or MEGA's newer share key store) is left out of
 *   listings but still counts as being in its folder, so a folder that only
 *   looks empty is never deleted with it.
 * - **Delete** is permanent (`d`), as [MegaSink]'s is: MEGA counts its
 *   rubbish bin against the quota.
 * - **Download** decrypts as it streams and checks the file's MAC at the
 *   end; a mismatch throws, and the store then discards what was written.
 *
 * Not done: new nodes in a folder this account shares out carry no key for
 * the share (go-mega leaves that out as well), so collaborators see them
 * only once a MEGA app adds it.
 */
class MegaFs(@Suppress("UNUSED_PARAMETER") context: Context, location: BackupLocation) : RemoteFs {

    private val config = location.mega

    private val api by lazy { MegaApi(config.sessionId) }

    private val http: OkHttpClient by lazy { BackupHttp.client() }

    private val master: ByteArray by lazy {
        if (!config.configured) throw IOException("MEGA is not signed in")
        runCatching { MegaCrypto.b64decode(config.masterKey) }.getOrNull()?.takeIf { it.size == MegaCrypto.KEY_BYTES }
            ?: throw IOException("the saved MEGA key does not read")
    }

    /** One node of the tree. [name], [key] and [attrs] are null when its key or attributes do not open. */
    private class Node(
        val handle: String,
        var parent: String,
        val type: Int,
        var name: String?,
        val size: Long,
        val modifiedMs: Long,
        /** 4 words for a folder, 8 for a file. */
        val key: IntArray?,
        /** Every attribute, so a rename keeps what it does not change. */
        var attrs: JsonObject?,
    )

    /**
     * The decrypted tree, indexed by parent. [own] is the account's user
     * handle and [shareKeys] the keys of the folders it shares out, both
     * needed again to read the nodes later calls hand back.
     */
    private class Tree(val root: String, val own: String?, val shareKeys: Map<String, ByteArray>) {
        val fetchedAt = System.currentTimeMillis()
        private val nodes = HashMap<String, Node>()
        private val kids = HashMap<String, MutableList<Node>>()

        operator fun get(handle: String): Node? = nodes[handle]

        fun add(node: Node) {
            nodes[node.handle]?.let { remove(it) }
            nodes[node.handle] = node
            kids.getOrPut(node.parent) { ArrayList() } += node
        }

        fun remove(node: Node) {
            nodes.remove(node.handle)
            kids[node.parent]?.remove(node)
        }

        fun reparent(node: Node, to: String) {
            kids[node.parent]?.remove(node)
            node.parent = to
            kids.getOrPut(to) { ArrayList() } += node
        }

        /** Everything directly in [handle], readable or not. */
        fun children(handle: String): List<Node> = kids[handle].orEmpty()
    }

    private var cached: Tree? = null

    /** The handle each twin path means, for the call now running. */
    private var picks: Map<List<String>, String> = emptyMap()

    override fun pick(picks: Map<List<String>, String>) {
        this.picks = picks
    }

    /**
     * The path an upload just failed at, before anything was made there: the
     * store's clean-up delete of that path then has nothing of ours to
     * remove, and must not remove what someone else put there meanwhile.
     */
    private var failedUpload: List<String>? = null

    private fun tree(): Tree {
        cached?.takeIf { System.currentTimeMillis() - it.fetchedAt < TREE_TTL_MS }?.let { return it }
        return freshTree()
    }

    private fun freshTree(): Tree {
        cached = null
        val answer = api.call(command("f") { put("c", JsonPrimitive(1)) }).jsonObject
        val items = answer["f"]?.jsonArray?.map { it.jsonObject } ?: throw IOException("MEGA sent no file list")
        val rootItem = items.firstOrNull { it.int("t") == TYPE_ROOT } ?: throw IOException("MEGA sent no cloud drive")
        val shareKeys = HashMap<String, ByteArray>()
        for (share in answer["ok"]?.jsonArray.orEmpty()) {
            val o = share.jsonObject
            val handle = o.str("h") ?: continue
            val wrapped = o.str("k") ?: continue
            runCatching { MegaCrypto.ecbDecrypt(master, MegaCrypto.b64decode(wrapped)) }.getOrNull()
                ?.takeIf { it.size == MegaCrypto.KEY_BYTES }?.let { shareKeys[handle] = it }
        }
        val tree = Tree(rootItem.str("h") ?: throw IOException("MEGA sent no cloud drive"), rootItem.str("u"), shareKeys)
        for (item in items) decode(item, tree)?.let(tree::add)
        return tree.also { cached = it }
    }

    /** A node from its JSON, decrypted as far as its key allows. */
    private fun decode(item: JsonObject, tree: Tree): Node? {
        val type = item.int("t") ?: return null
        val handle = item.str("h") ?: return null
        val parent = item.str("p").orEmpty()
        val modifiedMs = (item["ts"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000L
        if (type != TYPE_FILE && type != TYPE_FOLDER) return Node(handle, parent, type, null, 0L, modifiedMs, null, null)
        val key = unwrap(item.str("k"), tree.own ?: item.str("u"), tree.shareKeys)
            ?.let(MegaCrypto::toWords)
            ?.takeIf { type == TYPE_FOLDER || it.size >= FILE_KEY_WORDS }
        val attrs = key?.let { k -> item.str("a")?.let { decryptAttrs(MegaCrypto.toBytes(MegaCrypto.nodeKey(k)), it) } }
        val name = attrs?.get("n")?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }
        return Node(
            handle = handle,
            parent = parent,
            type = type,
            name = name,
            size = if (type == TYPE_FILE) item["s"]?.jsonPrimitive?.longOrNull ?: -1L else -1L,
            modifiedMs = modifiedMs,
            key = if (name == null) null else key,
            attrs = if (name == null) null else attrs,
        )
    }

    /**
     * The node key from `k`, `holder:key` pairs split by '/': the account's
     * own entry under the master key first, else a share's under that
     * share's key. Null when none opens. An RSA-wrapped key (something
     * shared to this account) is longer than 32 bytes and left alone.
     */
    private fun unwrap(k: String?, own: String?, shareKeys: Map<String, ByteArray>): ByteArray? {
        val parts = k?.split('/')?.map { it.substringBefore(':') to it.substringAfter(':', "") }?.filter { it.second.isNotEmpty() }
            ?: return null
        val ordered = parts.filter { it.first == own } + parts.filter { it.first != own }
        for ((holder, wrapped) in ordered) {
            val wrapKey = if (holder == own) master else shareKeys[holder] ?: continue
            val bytes = runCatching { MegaCrypto.b64decode(wrapped) }.getOrNull() ?: continue
            if (bytes.size != MegaCrypto.KEY_BYTES && bytes.size != 2 * MegaCrypto.KEY_BYTES) continue
            return runCatching { MegaCrypto.ecbDecrypt(wrapKey, bytes) }.getOrNull() ?: continue
        }
        return null
    }

    /**
     * The node at [path], null when nothing is. Where two items share a name
     * on the way, the picked handle (null once it is gone); no pick throws.
     */
    private fun resolve(tree: Tree, path: List<String>): Node? {
        var at = tree[tree.root] ?: throw IOException("MEGA sent no cloud drive")
        for ((i, name) in path.withIndex()) {
            if (at.type == TYPE_FILE) return null
            val matches = tree.children(at.handle).filter { it.name == name && (it.type == TYPE_FILE || it.type == TYPE_FOLDER) }
            val picked = picks[path.subList(0, i + 1)]
            at = when {
                picked != null -> matches.firstOrNull { it.handle == picked } ?: return null
                matches.isEmpty() -> return null
                matches.size == 1 -> matches[0]
                else -> throw IOException("more than one item is called $name there")
            }
        }
        return at
    }

    private fun folder(tree: Tree, path: List<String>): Node =
        resolve(tree, path)?.takeIf { it.type == TYPE_FOLDER || it.type == TYPE_ROOT } ?: throw IOException("the folder is not there")

    private fun entryOf(node: Node): RemoteEntry =
        RemoteEntry(node.name.orEmpty(), node.type != TYPE_FILE, if (node.type == TYPE_FILE) node.size else -1L, node.modifiedMs, key = node.handle)

    override fun list(dir: List<String>): List<RemoteEntry> {
        failedUpload = null
        val tree = tree()
        val at = folder(tree, dir)
        return tree.children(at.handle).filter { it.name != null && (it.type == TYPE_FILE || it.type == TYPE_FOLDER) }.map(::entryOf)
    }

    override fun stat(path: List<String>): RemoteEntry? {
        failedUpload = null
        if (path.isEmpty()) return RemoteEntry("", true)
        return resolve(tree(), path)?.let(::entryOf)
    }

    override fun download(path: List<String>, to: OutputStream) {
        failedUpload = null
        val node = resolve(tree(), path)?.takeIf { it.type == TYPE_FILE } ?: throw IOException("the file is not there")
        val key = node.key ?: throw IOException("the file's key does not open")
        val answer = api.call(
            command("g") {
                put("g", JsonPrimitive(1))
                put("n", JsonPrimitive(node.handle))
                put("ssl", JsonPrimitive(2))
            },
        ).jsonObject
        val url = (answer["g"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") }
            ?: throw IOException("MEGA gave no download address")
        val size = answer["s"]?.jsonPrimitive?.longOrNull ?: node.size
        if (size < 0) throw IOException("MEGA did not say how big the file is")
        http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MEGA download: HTTP ${response.code}")
            val body = response.body ?: throw IOException("MEGA download: no body")
            decryptTo(body.byteStream(), size, key, to)
        }
    }

    /**
     * Decrypts [size] bytes of [input] chunk by chunk into [to], checking the
     * MAC once all are through. Only one chunk (at most 1 MiB) is held at a
     * time. Throws when the stream is short or long, or the MAC is wrong.
     */
    private fun decryptTo(input: InputStream, size: Long, key: IntArray, to: OutputStream) {
        val aesKey = MegaCrypto.toBytes(MegaCrypto.nodeKey(key))
        val ctr = ctr(Cipher.DECRYPT_MODE, aesKey, key[4], key[5])
        val mac = ChunkMac(aesKey, key[4], key[5])
        val data = DataInputStream(input)
        val buffer = ByteArray(MAX_CHUNK)
        for ((_, length) in MegaCrypto.chunks(size)) {
            val n = length.toInt()
            data.readFully(buffer, 0, n)
            val plain = ctr.update(buffer, 0, n) ?: ByteArray(0)
            if (plain.size != n) throw IOException("the decryption fell out of step")
            mac.add(plain)
            to.write(plain)
        }
        if (data.read() != -1) throw IOException("MEGA sent more than the file")
        // An empty file has no chunk to check, and MEGA's clients disagree on its MAC.
        if (size > 0) {
            val words = mac.words()
            if (words[0] != key[6] || words[1] != key[7]) throw IOException("the file does not match its MAC")
        }
    }

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        failedUpload = null
        val (tree, made) = try {
            val tree = tree()
            tree to send(folder(tree, dir), name, from, onSent)
        } catch (failure: Throwable) {
            failedUpload = dir + name
            throw failure
        }
        // Read back from MEGA's own answer, so the store's size check sees what MEGA holds.
        val nodes = made["f"]?.jsonArray?.mapNotNull { decode(it.jsonObject, tree) }.orEmpty()
        if (nodes.isEmpty()) cached = null else nodes.forEach(tree::add)
    }

    /**
     * Encrypts [from] and sends it chunk by chunk (AES-CTR under a fresh key,
     * a CBC-MAC per chunk), then makes the node with `p`. MEGA's answer to
     * `p`. Nothing appears in the folder before that last call.
     */
    private fun send(parent: Node, name: String, from: File, onSent: (Long) -> Unit): JsonObject {
        val size = from.length()
        val target = (
            api.call(
                command("u") {
                    put("s", JsonPrimitive(size))
                    put("ssl", JsonPrimitive(2))
                },
            ).jsonObject["p"] as? JsonPrimitive
            )?.contentOrNull?.takeIf { it.startsWith("http") } ?: throw IOException("MEGA gave no upload address")

        val key = MegaCrypto.toWords(MegaCrypto.random(FILE_RANDOM_BYTES))
        val aesKey = MegaCrypto.toBytes(key.copyOf(4))
        val ctr = ctr(Cipher.ENCRYPT_MODE, aesKey, key[4], key[5])
        val mac = ChunkMac(aesKey, key[4], key[5])
        var completion = ""
        DataInputStream(from.inputStream().buffered(MAX_CHUNK)).use { input ->
            // An empty file is still one (empty) chunk: its answer is the completion handle.
            val chunks = MegaCrypto.chunks(size).ifEmpty { listOf(0L to 0L) }
            val buffer = ByteArray(MAX_CHUNK)
            for ((at, length) in chunks) {
                val n = length.toInt()
                input.readFully(buffer, 0, n)
                val plain = buffer.copyOf(n)
                mac.add(plain)
                val sealed = if (n == 0) ByteArray(0) else ctr.update(plain) ?: ByteArray(0)
                if (sealed.size != n) throw IOException("the encryption fell out of step")
                completion = api.upload("$target/$at", sealed)
                onSent(n.toLong())
            }
            if (input.read() != -1) throw IOException("the file grew while it was sent")
        }
        if (completion.isEmpty()) throw IOException("MEGA did not finish the upload")

        val m = mac.words()
        val nodeKey = intArrayOf(
            key[0] xor key[4], key[1] xor key[5], key[2] xor m[0], key[3] xor m[1],
            key[4], key[5], m[0], m[1],
        )
        return api.call(
            command("p") {
                put("t", JsonPrimitive(parent.handle))
                put(
                    "n",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("h", JsonPrimitive(completion))
                                put("t", JsonPrimitive(TYPE_FILE))
                                put("a", JsonPrimitive(encryptAttrs(aesKey, nameOnly(name))))
                                put("k", JsonPrimitive(MegaCrypto.b64encode(MegaCrypto.ecbEncrypt(master, MegaCrypto.toBytes(nodeKey)))))
                            },
                        ),
                    ),
                )
                put("i", JsonPrimitive(MegaCrypto.requestId()))
            },
        ).jsonObject
    }

    override fun mkdir(dir: List<String>, name: String) {
        failedUpload = null
        val tree = tree()
        val parent = folder(tree, dir)
        val key = MegaCrypto.random(MegaCrypto.KEY_BYTES)
        val made = api.call(
            command("p") {
                put("t", JsonPrimitive(parent.handle))
                put(
                    "n",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("h", JsonPrimitive("xxxxxxxx"))
                                put("t", JsonPrimitive(TYPE_FOLDER))
                                put("a", JsonPrimitive(encryptAttrs(key, nameOnly(name))))
                                put("k", JsonPrimitive(MegaCrypto.b64encode(MegaCrypto.ecbEncrypt(master, key))))
                            },
                        ),
                    ),
                )
                put("i", JsonPrimitive(MegaCrypto.requestId()))
            },
        ).jsonObject
        val nodes = made["f"]?.jsonArray?.mapNotNull { decode(it.jsonObject, tree) }.orEmpty()
        if (nodes.isEmpty()) cached = null else nodes.forEach(tree::add)
    }

    override fun delete(path: List<String>, isDirectory: Boolean) {
        if (failedUpload == path) {
            failedUpload = null
            return
        }
        failedUpload = null
        // A folder is judged empty on the tree as it is now, not as it was.
        val tree = if (isDirectory) freshTree() else tree()
        val node = resolve(tree, path) ?: return
        if (node.type != TYPE_FILE && node.type != TYPE_FOLDER) throw IOException("not supported here")
        if ((node.type == TYPE_FOLDER) != isDirectory) throw IOException("not the kind of item that was asked for")
        // `d` takes a folder's contents with it: only an empty one goes, and
        // nodes this session cannot read count as contents too.
        if (node.type == TYPE_FOLDER && tree.children(node.handle).isNotEmpty()) throw IOException("the folder is not empty")
        try {
            api.callRaw(
                command("d") {
                    put("n", JsonPrimitive(node.handle))
                    put("i", JsonPrimitive(MegaCrypto.requestId()))
                },
            )
        } catch (error: MegaError) {
            if (error.code != ENOENT) throw BackupSinkException(MegaSink.errorOf(error.code), error)
        }
        tree.remove(node)
    }

    override fun rename(path: List<String>, newName: String) {
        failedUpload = null
        val tree = tree()
        val node = resolve(tree, path) ?: throw IOException("nothing to rename")
        if (node.type != TYPE_FILE && node.type != TYPE_FOLDER) throw IOException("not supported here")
        val key = node.key ?: throw IOException("the item's key does not open")
        val attrs = node.attrs ?: throw IOException("the item's attributes do not open")
        if (tree.children(node.parent).any { it !== node && it.name == newName }) throw IOException("$newName is taken")
        // Every other attribute (MEGA's fingerprint, labels) is kept; only the name changes.
        val changed = JsonObject(attrs + ("n" to JsonPrimitive(newName)))
        val aesKey = MegaCrypto.toBytes(MegaCrypto.nodeKey(key))
        val sealed = encryptAttrs(aesKey, changed)
        if (decryptAttrs(aesKey, sealed)?.get("n")?.jsonPrimitive?.contentOrNull != newName) throw IOException("the new name does not read back")
        // As go-mega sends it: the attributes, and the node key wrapped again under the master key.
        api.call(
            command("a") {
                put("attr", JsonPrimitive(sealed))
                put("key", JsonPrimitive(MegaCrypto.b64encode(MegaCrypto.ecbEncrypt(master, MegaCrypto.toBytes(key)))))
                put("n", JsonPrimitive(node.handle))
                put("i", JsonPrimitive(MegaCrypto.requestId()))
            },
        )
        node.name = newName
        node.attrs = changed
    }

    override fun move(path: List<String>, toDir: List<String>): Boolean {
        failedUpload = null
        val tree = tree()
        val node = resolve(tree, path) ?: throw IOException("nothing to move")
        if (node.type != TYPE_FILE && node.type != TYPE_FOLDER) throw IOException("not supported here")
        val destination = folder(tree, toDir)
        // Never into itself or anything inside it.
        var at: Node? = destination
        while (at != null) {
            if (at.handle == node.handle) throw IOException("a folder cannot go inside itself")
            at = tree[at.parent]
        }
        api.call(
            command("m") {
                put("n", JsonPrimitive(node.handle))
                put("t", JsonPrimitive(destination.handle))
                put("i", JsonPrimitive(MegaCrypto.requestId()))
            },
        )
        tree.reparent(node, destination.handle)
        return true
    }

    override val atomicUpload: Boolean get() = true

    // ---- Helpers ---------------------------------------------------------------------

    private fun command(action: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("a", JsonPrimitive(action))
            body()
        }

    private fun nameOnly(name: String): JsonObject = buildJsonObject { put("n", JsonPrimitive(name)) }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun ctr(mode: Int, key: ByteArray, n0: Int, n1: Int): Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(MegaCrypto.toBytes(intArrayOf(n0, n1, 0, 0))))
    }

    /**
     * MEGA's file MAC, a chunk at a time: a CBC-MAC of each chunk under the
     * nonce, zero-padded to a block, and those chained by CBC again; folded to
     * two words at the end. The same sum [MegaCrypto] makes over a whole file.
     */
    private class ChunkMac(key: ByteArray, n0: Int, n1: Int) {
        private val keySpec = SecretKeySpec(key, "AES")
        private val chunkIv = IvParameterSpec(MegaCrypto.toBytes(intArrayOf(n0, n1, n0, n1)))
        private val block = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keySpec) }
        private var last = ByteArray(BLOCK)

        fun add(chunk: ByteArray) {
            if (chunk.isEmpty()) return
            val padded = if (chunk.size % BLOCK == 0) chunk else chunk.copyOf((chunk.size + BLOCK - 1) / BLOCK * BLOCK)
            val inner = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keySpec, chunkIv) }.doFinal(padded)
            val mixed = ByteArray(BLOCK) { (last[it].toInt() xor inner[inner.size - BLOCK + it].toInt()).toByte() }
            last = block.doFinal(mixed)
        }

        fun words(): IntArray {
            val t = MegaCrypto.toWords(last)
            return intArrayOf(t[0] xor t[1], t[2] xor t[3])
        }
    }

    companion object {
        private const val TYPE_FILE = 0
        private const val TYPE_FOLDER = 1
        private const val TYPE_ROOT = 2
        private const val FILE_KEY_WORDS = 8
        private const val FILE_RANDOM_BYTES = 24
        private const val ENOENT = -9
        private const val BLOCK = 16
        private const val MAX_CHUNK = 1_048_576

        /** How long a fetched tree answers for itself before it is fetched again. */
        private const val TREE_TTL_MS = 15_000L

        private val json = Json { ignoreUnknownKeys = true }

        /** `MEGA` and the attributes' JSON, zero-padded and AES-CBC encrypted under a zero IV. */
        private fun encryptAttrs(key: ByteArray, attrs: JsonObject): String {
            val raw = ("MEGA" + attrs.toString()).toByteArray(Charsets.UTF_8)
            val padded = raw.copyOf((raw.size + BLOCK - 1) / BLOCK * BLOCK)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(BLOCK)))
            }
            return MegaCrypto.b64encode(cipher.doFinal(padded))
        }

        /** Every attribute of a node, or null when they do not open with [key]. */
        private fun decryptAttrs(key: ByteArray, at: String): JsonObject? = runCatching {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(BLOCK)))
            }
            val text = cipher.doFinal(MegaCrypto.b64decode(at)).toString(Charsets.UTF_8)
            if (!text.startsWith("MEGA")) return null
            val body = text.substring(4).trimEnd('\u0000')
            val start = body.indexOf('{')
            val end = body.lastIndexOf('}')
            if (start < 0 || end < start) return null
            json.parseToJsonElement(body.substring(start, end + 1)).jsonObject
        }.getOrNull()
    }
}

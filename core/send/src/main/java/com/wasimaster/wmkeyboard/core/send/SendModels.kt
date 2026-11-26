package com.wasimaster.wmkeyboard.core.send

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** What went wrong, as the panel needs to tell it apart; the HTTP status rides along for the log. */
enum class SendError {
    /** No route to the server, or it stopped answering. */
    OFFLINE,
    /** The server's size limit (413). */
    TOO_BIG,
    /** Expiry or download count outside what the server allows (400). */
    BAD_LIMITS,
    /** The server wants a signed-in uploader (401 on upload). */
    UNAUTHORIZED,
    /** The file is gone: expired, deleted, or downloaded its last time. */
    NOT_FOUND,
    SERVER,
    CANCELLED,
    /** The link's secret does not open this file's envelope or content. */
    WRONG_KEY,
    /** The file has a password and the one given is not it. */
    WRONG_PASSWORD,
    /** The ciphertext is damaged or cut short. */
    CORRUPT,
    /** Not a Send link at all. */
    BAD_LINK,
    /** A file to send could not be opened: moved, deleted, or its read grant gone. */
    UNREADABLE,
}

class SendException(
    val error: SendError,
    val status: Int = 0,
    val detail: String? = null,
    cause: Throwable? = null,
) : IOException(
    buildString {
        append(error.name)
        if (status != 0) append(" (").append(status).append(')')
        if (detail != null) append(": ").append(detail)
    },
    cause,
)

const val OCTET_STREAM = "application/octet-stream"

/** One file inside an upload, as the metadata envelope lists it. */
@Serializable
data class SendFileEntry(val name: String, val size: Long, val type: String = OCTET_STREAM)

/**
 * The envelope a link opens: the archive's name, size and type, and the files
 * inside it. A single file is its own archive; several travel as one plain
 * concatenation named `Send-Archive.zip` of type `send-archive`, which the web
 * client zips on the way down and this client splits by the manifest.
 */
data class SendMetadata(
    val name: String,
    val size: Long,
    val type: String,
    val files: List<SendFileEntry>,
) {
    val isArchive: Boolean get() = type == ARCHIVE_TYPE

    /** What to save: the manifest's files for an archive, the archive itself otherwise. */
    val saved: List<SendFileEntry>
        get() = if (isArchive && files.isNotEmpty()) files else listOf(SendFileEntry(name, size, type))

    fun toJson(): String = buildJsonObject {
        put("name", name)
        put("size", size)
        put("type", type)
        put("manifest", buildJsonObject {
            put("files", buildJsonArray {
                files.forEach { f ->
                    add(buildJsonObject {
                        put("name", f.name)
                        put("size", f.size)
                        put("type", f.type)
                    })
                }
            })
        })
    }.toString()

    companion object {
        const val ARCHIVE_TYPE = "send-archive"
        const val ARCHIVE_NAME = "Send-Archive.zip"

        fun forFiles(files: List<SendFileEntry>): SendMetadata {
            require(files.isNotEmpty())
            val total = files.sumOf { it.size }
            return if (files.size == 1) {
                SendMetadata(files[0].name, files[0].size, files[0].type, files)
            } else {
                SendMetadata(ARCHIVE_NAME, total, ARCHIVE_TYPE, files)
            }
        }

        /**
         * Lenient on purpose: older uploads carry no manifest, some clients
         * write the size as a string, and names used to be URI-encoded.
         */
        fun fromJson(text: String): SendMetadata {
            val obj = runCatching { Json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw SendException(SendError.CORRUPT, detail = "metadata is not JSON") }
            val files = (obj["manifest"] as? JsonObject)?.get("files")?.let { it as? JsonArray }?.map { e ->
                val f = e.jsonObject
                SendFileEntry(
                    name = decodeName(f.string("name") ?: "file"),
                    size = f.long("size") ?: 0L,
                    type = f.string("type")?.ifBlank { null } ?: OCTET_STREAM,
                )
            }.orEmpty()
            return SendMetadata(
                name = decodeName(obj.string("name") ?: "file"),
                size = obj.long("size") ?: files.sumOf { it.size },
                type = obj.string("type")?.ifBlank { null } ?: OCTET_STREAM,
                files = files,
            )
        }

        /** Undoes `encodeURIComponent`, and only that: a literal `+` in a name stays a `+`. */
        internal fun decodeName(raw: String): String {
            if (!raw.contains('%')) return raw
            return runCatching {
                java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            }.getOrDefault(raw)
        }
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

/**
 * A share link taken apart: `https://host/download/<id>/#<secret>`. The id is
 * ten to sixteen hex digits, the fragment the 16-byte secret in URL-safe
 * base64. Any host counts, since every instance uses the same path, which is
 * also how a link in a chat is recognised without a list of servers.
 */
class SendLink(base: String, val id: String, val secret: ByteArray) {
    /** Scheme and host (and any path prefix the instance lives under), no trailing slash. */
    val base: String = base.trimEnd('/')

    val secretB64: String get() = Base64Url.encode(secret)

    /**
     * The canonical form, trailing slash before the fragment included. It is
     * also the PBKDF2 salt for a password, so it must match the web client's
     * address bar byte for byte.
     */
    val url: String get() = "$base/download/$id/#$secretB64"

    val keys: SendKeys get() = SendKeys(secret)

    override fun equals(other: Any?): Boolean = other is SendLink && other.url == url
    override fun hashCode(): Int = url.hashCode()
    override fun toString(): String = url

    companion object {
        // Built on first use: each compiled Regex holds native ICU sets for the
        // life of the process, about 9 KB across this file's patterns.
        private val PATTERN by lazy {
            Regex(
                """(https?://[^\s/#?]+(?:/[^\s#?]*?)?)/download/([0-9a-fA-F]{10,16})/?(?:\?[^\s#]*)?#([A-Za-z0-9_-]{22})(?![A-Za-z0-9_-])""",
            )
        }

        /** The link, if the whole of [text] (trimmed) is one. */
        fun parse(text: String): SendLink? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            return from(m)
        }

        /**
         * The first Send link anywhere in [text]: typed, pasted, or buried in a
         * message. The smart strip asks on every keystroke, and no link lacks
         * the pattern's literal "/download/", so plain text never compiles it.
         */
        fun find(text: String): SendLink? = if ("/download/" !in text) null else PATTERN.find(text)?.let(::from)

        private fun from(m: MatchResult): SendLink? {
            val secret = runCatching { Base64Url.decode(m.groupValues[3]) }.getOrNull() ?: return null
            if (secret.size != SendKeys.SECRET_LENGTH) return null
            return SendLink(m.groupValues[1], m.groupValues[2].lowercase(), secret)
        }
    }
}

/** What `GET /config` says an instance allows; [DEFAULT] is the stock server. */
data class SendServerConfig(
    val maxFileSize: Long = 2_684_354_560L,
    val maxDownloads: Int = 100,
    val maxExpireSeconds: Long = 604_800L,
    val maxFilesPerArchive: Int = 64,
    val defaultDownloads: Int = 1,
    val downloadCounts: List<Int> = listOf(1, 2, 3, 4, 5, 20, 50, 100),
    val expireTimes: List<Long> = listOf(300L, 3_600L, 86_400L, 604_800L),
    val defaultExpireSeconds: Long = 86_400L,
    val footer: String = "",
) {
    /** The expiry choices the panel may offer: the server's list, capped at its maximum. */
    val expireChoices: List<Long> get() = expireTimes.filter { it in 1..maxExpireSeconds }.ifEmpty { listOf(maxExpireSeconds) }

    val downloadChoices: List<Int> get() = downloadCounts.filter { it in 1..maxDownloads }.ifEmpty { listOf(maxDownloads) }

    companion object {
        val DEFAULT = SendServerConfig()

        fun fromJson(text: String): SendServerConfig {
            val obj = runCatching { Json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw SendException(SendError.SERVER, detail = "config is not JSON") }
            val limits = obj["LIMITS"] as? JsonObject
            val defaults = obj["DEFAULTS"] as? JsonObject
            val ui = obj["WEB_UI"] as? JsonObject
            val d = DEFAULT
            return SendServerConfig(
                maxFileSize = limits?.long("MAX_FILE_SIZE") ?: d.maxFileSize,
                maxDownloads = limits?.int("MAX_DOWNLOADS") ?: d.maxDownloads,
                maxExpireSeconds = limits?.long("MAX_EXPIRE_SECONDS") ?: d.maxExpireSeconds,
                maxFilesPerArchive = limits?.int("MAX_FILES_PER_ARCHIVE") ?: d.maxFilesPerArchive,
                defaultDownloads = defaults?.int("DOWNLOADS") ?: d.defaultDownloads,
                downloadCounts = defaults?.get("DOWNLOAD_COUNTS")?.let { it as? JsonArray }
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }?.ifEmpty { null } ?: d.downloadCounts,
                expireTimes = defaults?.get("EXPIRE_TIMES_SECONDS")?.let { it as? JsonArray }
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toLongOrNull() }?.ifEmpty { null } ?: d.expireTimes,
                defaultExpireSeconds = defaults?.long("EXPIRE_SECONDS") ?: d.defaultExpireSeconds,
                footer = ui?.string("CUSTOM_FOOTER_TEXT").orEmpty(),
            )
        }
    }
}

/** What the uploader asks for. */
data class SendUploadOptions(
    val timeLimitSeconds: Long = 86_400L,
    val downloadLimit: Int = 1,
    /** Set after the upload through `/api/password`; null leaves the file open to anyone with the link. */
    val password: String? = null,
)

/** A file to upload. [open] is called once, on the uploading thread. */
class SendFile(
    val name: String,
    val size: Long,
    val type: String = OCTET_STREAM,
    val open: () -> InputStream,
) {
    val entry: SendFileEntry get() = SendFileEntry(name, size, type)
}

/** An upload this device made, remembered so its link can be shown again and the file managed or deleted. */
@Serializable
data class SendUpload(
    val id: String,
    /** The full share link, secret included. */
    val url: String,
    val ownerToken: String,
    /** The instance base URL, for the owner calls. */
    val instance: String,
    val name: String,
    val size: Long,
    val type: String,
    val files: List<SendFileEntry>,
    val createdAt: Long,
    val expiresAt: Long,
    val downloadLimit: Int,
    val password: Boolean,
) {
    val link: SendLink? get() = SendLink.parse(url)

    fun expired(nowMs: Long): Boolean = nowMs >= expiresAt
}

/** A file this device received through a link, for the history list. */
@Serializable
data class SendReceived(
    val name: String,
    val size: Long,
    val type: String,
    /** Where it was saved, as the host's own reference (a URI string). */
    val location: String,
    /** The instance host it came from. */
    val from: String,
    val at: Long,
)

/** `/api/info`: how the file is doing on the server. */
data class SendInfo(val downloadLimit: Int, val downloads: Int, val ttlMs: Long)

/** What a link points at, once the server has been asked. */
data class SendRemoteFile(
    val link: SendLink,
    val metadata: SendMetadata,
    val ttlMs: Long,
    /** The next download is the last the server allows; it deletes the file after. */
    val finalDownload: Boolean,
    val requiresPassword: Boolean,
)

/**
 * Where received files go. The protocol knows nothing about storage: the host
 * opens a destination per file and is told how it ended.
 */
interface SendSink {
    /** Null refuses the file. Called on the downloading thread. */
    fun create(name: String, size: Long, type: String): SendSinkFile?
}

interface SendSinkFile {
    val stream: OutputStream

    /** Closes [stream]. Returns the location to remember on success, or null. */
    fun finish(success: Boolean): String?
}

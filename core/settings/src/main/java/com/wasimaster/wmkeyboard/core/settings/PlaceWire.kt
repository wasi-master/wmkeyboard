package com.wasimaster.wmkeyboard.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A place for copies as it goes between WM Keyboard and WM Pass, so a place
 * set up in one app can be picked in the other. It crosses once, when the
 * person picks it on the other app's screen, as the result of that screen:
 * held in memory, never written anywhere on the way, and only to an app whose
 * signing certificate checked out. Each app then keeps its own copy, stored
 * its own way; nothing keeps the two in step afterwards.
 *
 * WM Pass reads and writes the same format in its `PlaceWire`. A change here
 * is a change there too.
 *
 * One JSON object, version 1. `kind` is the place's kind as both apps name
 * it (`webdav`, `sftp`, `pcloud`...); every other key may be missing and
 * reads as empty, or as the kind's own default:
 *
 * - `name`: what the person called it.
 * - `webdav`: `url`, `user`, `password`.
 * - `s3`: `endpoint`, `region`, `bucket`, `prefix`, `accessKeyId`,
 *   `secretAccessKey`, `pathStyle`.
 * - `sftp`: `host`, `port`, `user`, `password`, `privateKey`,
 *   `keyPassphrase`, `path`, `hostKey`.
 * - `ftp`: `host`, `port`, `user`, `password`, `path`, `secure`.
 * - `smb`: `host`, `port`, `share`, `path`, `domain`, `user`, `password`, `encrypt`.
 * - `nfs`: `host`, `export`, `path`, `uid`, `gid`, `port`, `mountPort`.
 * - `git`: `provider`, `url` (the server, empty for the provider's own),
 *   `repository`, `branch`, `path`, `token`, `allowPublic`.
 * - `imap`: `host`, `port`, `security`, `user`, `password`, `folder` (the mailbox).
 * - `synology`, `qnap`: `host` (the server), `user`, `password`, `path`,
 *   `ownCertificate`, `certPin`.
 * - `drive`: `driveSpace`, `folder`. `box`: `folder`.
 * - `pcloud`: `pcloudRegion`, `user` (the email), `folder`. `mega`, `proton`:
 *   `user`, `folder`. `icloud`: `user` (the Apple Account), `password`, `folder`.
 * - `folder`, `dropbox`, `onedrive`: nothing more.
 *
 * An empty `folder` is the receiving app's own default; a sender whose place
 * still uses its own default sends none, so WM Pass's copies do not land in a
 * folder called "WM Keyboard backups", or the other way round.
 *
 * What never crosses is a sign-in. The OAuth kinds' tokens belong to each
 * app's own registration with the service and would not work in the other
 * one; Box's and Proton's rotate on every use, so two apps sharing one would
 * log each other out; pCloud, MEGA and iCloud keep sessions, and a NAS keeps
 * the token that lets it skip a 2FA code. The other app signs in again for
 * each of those. A folder on the phone crosses without its folder, because
 * Android grants a folder to one app, and the other app asks for it again.
 * Logins a server simply checks (passwords, keys, a Git token) cross whole,
 * which is what saves typing them twice.
 */
object PlaceWire {
    const val VERSION = 1

    /** The extra both apps' place pickers put the place in. */
    const val EXTRA = "com.wasimaster.suite.extra.PLACE"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [location] as the other app reads it, or null for a kind it has no use
     * for: a nearby computer is paired to this app alone.
     */
    fun encode(location: BackupLocation): String? {
        if (location.type == BackupDestination.NEARBY) return null
        val l = location
        return buildJsonObject {
            put("v", VERSION)
            put("kind", l.type.id)
            put("name", l.name)
            when (l.type) {
                BackupDestination.FOLDER,
                BackupDestination.DROPBOX,
                BackupDestination.ONEDRIVE,
                BackupDestination.NEARBY,
                -> Unit
                BackupDestination.WEBDAV -> {
                    put("url", l.webDavUrl)
                    put("user", l.webDavUser)
                    put("password", l.webDavPassword)
                }
                BackupDestination.S3 -> with(l.s3) {
                    put("endpoint", endpoint)
                    put("region", region)
                    put("bucket", bucket)
                    put("prefix", prefix)
                    put("accessKeyId", accessKeyId)
                    put("secretAccessKey", secretAccessKey)
                    put("pathStyle", pathStyle)
                }
                BackupDestination.SFTP -> with(l.sftp) {
                    put("host", host)
                    put("port", port)
                    put("user", user)
                    put("password", password)
                    put("privateKey", privateKey)
                    put("keyPassphrase", keyPassphrase)
                    put("path", path)
                    put("hostKey", hostKey)
                }
                BackupDestination.FTP -> with(l.ftp) {
                    put("host", host)
                    put("port", port)
                    put("user", user)
                    put("password", password)
                    put("path", path)
                    put("secure", secure)
                }
                BackupDestination.SMB -> with(l.smb) {
                    put("host", host)
                    put("port", port)
                    put("share", share)
                    put("path", path)
                    put("domain", domain)
                    put("user", user)
                    put("password", password)
                    put("encrypt", encrypt)
                }
                BackupDestination.NFS -> with(l.nfs) {
                    put("host", host)
                    put("export", export)
                    put("path", path)
                    put("uid", uid)
                    put("gid", gid)
                    put("port", port)
                    put("mountPort", mountPort)
                }
                BackupDestination.GIT -> with(l.git) {
                    put("provider", provider.id)
                    put("url", server)
                    put("repository", repository)
                    put("branch", branch)
                    put("path", path)
                    put("token", token)
                    put("allowPublic", allowPublic)
                }
                BackupDestination.IMAP -> with(l.imap) {
                    put("host", host)
                    put("port", port)
                    put("security", security.id)
                    put("user", user)
                    put("password", password)
                    put("folder", ownFolder(mailbox, ImapConfig.DEFAULT_MAILBOX))
                }
                BackupDestination.SYNOLOGY, BackupDestination.QNAP -> with(l.nas) {
                    put("host", server)
                    put("user", user)
                    put("password", password)
                    put("path", path)
                    put("ownCertificate", ownCertificate)
                    put("certPin", certPin)
                }
                BackupDestination.DRIVE -> {
                    put("driveSpace", l.driveSpace.id)
                    put("folder", ownFolder(l.driveFolder, DriveSpace.DEFAULT_FOLDER))
                }
                BackupDestination.BOX -> put("folder", ownFolder(l.box.folder, BoxConfig.DEFAULT_FOLDER))
                BackupDestination.PCLOUD -> with(l.pcloud) {
                    put("pcloudRegion", if (region == PCloudRegion.EU) "eu" else "us")
                    put("user", email)
                    put("folder", ownFolder(folder, PCloudConfig.DEFAULT_FOLDER))
                }
                BackupDestination.MEGA -> with(l.mega) {
                    put("user", email)
                    put("folder", ownFolder(folder, MegaConfig.DEFAULT_FOLDER))
                }
                BackupDestination.ICLOUD -> with(l.icloud) {
                    put("user", appleId)
                    put("password", password)
                    put("folder", ownFolder(folder, ICloudConfig.DEFAULT_FOLDER))
                }
                BackupDestination.PROTON -> with(l.proton) {
                    put("user", user)
                    put("folder", ownFolder(folder, ProtonConfig.DEFAULT_FOLDER))
                }
            }
        }.toString()
    }

    /**
     * A new location under [id] from what the other app sent, or null when it
     * does not parse or names a kind this app does not know. Not saved: the
     * caller shows it to the person first, who signs in or picks the folder
     * where one is needed, and saves it.
     */
    fun decode(raw: String, id: String = BackupLocation.newId()): BackupLocation? {
        val o = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val type = BackupDestination.entries.firstOrNull { it.id == o.str("kind") } ?: return null
        if (type == BackupDestination.NEARBY) return null
        val base = BackupLocation(id = id, type = type, name = o.str("name").trim())
        val folder = o.str("folder").trim()
        return when (type) {
            BackupDestination.FOLDER,
            BackupDestination.DROPBOX,
            BackupDestination.ONEDRIVE,
            BackupDestination.NEARBY,
            -> base
            BackupDestination.WEBDAV -> base.copy(
                webDavUrl = o.str("url"),
                webDavUser = o.str("user"),
                webDavPassword = o.str("password"),
            )
            BackupDestination.S3 -> base.copy(
                s3 = S3Config(
                    endpoint = o.str("endpoint"),
                    region = o.str("region").ifEmpty { "us-east-1" },
                    bucket = o.str("bucket"),
                    prefix = o.str("prefix"),
                    accessKeyId = o.str("accessKeyId"),
                    secretAccessKey = o.str("secretAccessKey"),
                    pathStyle = o.flag("pathStyle", false),
                ),
            )
            BackupDestination.SFTP -> base.copy(
                sftp = SftpConfig(
                    host = o.str("host"),
                    port = o.int("port", 22),
                    user = o.str("user"),
                    password = o.str("password"),
                    privateKey = o.str("privateKey"),
                    keyPassphrase = o.str("keyPassphrase"),
                    path = o.str("path"),
                    hostKey = o.str("hostKey"),
                ),
            )
            BackupDestination.FTP -> base.copy(
                ftp = FtpConfig(
                    host = o.str("host"),
                    port = o.int("port", 21),
                    user = o.str("user"),
                    password = o.str("password"),
                    path = o.str("path"),
                    secure = o.flag("secure", true),
                ),
            )
            BackupDestination.SMB -> base.copy(
                smb = SmbConfig(
                    host = o.str("host"),
                    port = o.int("port", 445),
                    share = o.str("share"),
                    path = o.str("path"),
                    domain = o.str("domain"),
                    user = o.str("user"),
                    password = o.str("password"),
                    encrypt = o.flag("encrypt", true),
                ),
            )
            BackupDestination.NFS -> base.copy(
                nfs = NfsConfig(
                    host = o.str("host"),
                    export = o.str("export"),
                    path = o.str("path"),
                    uid = o.int("uid", NfsConfig.DEFAULT_ID),
                    gid = o.int("gid", NfsConfig.DEFAULT_ID),
                    port = o.int("port", 0),
                    mountPort = o.int("mountPort", 0),
                ),
            )
            BackupDestination.GIT -> base.copy(
                git = GitConfig(
                    provider = GitProvider.of(o.str("provider")),
                    server = o.str("url"),
                    repository = o.str("repository"),
                    branch = o.str("branch"),
                    path = o.str("path"),
                    token = o.str("token"),
                    allowPublic = o.flag("allowPublic", false),
                ),
            )
            BackupDestination.IMAP -> {
                val security = ImapSecurity.of(o.str("security"))
                base.copy(
                    imap = ImapConfig(
                        host = o.str("host"),
                        port = o.int("port", security.defaultPort),
                        security = security,
                        user = o.str("user"),
                        password = o.str("password"),
                        mailbox = folder.ifEmpty { ImapConfig.DEFAULT_MAILBOX },
                    ),
                )
            }
            BackupDestination.SYNOLOGY, BackupDestination.QNAP -> base.copy(
                nas = NasConfig(
                    server = o.str("host"),
                    user = o.str("user"),
                    password = o.str("password"),
                    path = o.str("path"),
                    ownCertificate = o.flag("ownCertificate", false),
                    certPin = o.str("certPin"),
                ),
            )
            BackupDestination.DRIVE -> base.copy(
                driveSpace = DriveSpace.of(o.str("driveSpace")),
                driveFolder = folder.ifEmpty { DriveSpace.DEFAULT_FOLDER },
            )
            BackupDestination.BOX -> base.copy(box = BoxConfig(folder.ifEmpty { BoxConfig.DEFAULT_FOLDER }))
            BackupDestination.PCLOUD -> base.copy(
                pcloud = PCloudConfig(
                    region = if (o.str("pcloudRegion") == "eu") PCloudRegion.EU else PCloudRegion.US,
                    email = o.str("user"),
                    folder = folder.ifEmpty { PCloudConfig.DEFAULT_FOLDER },
                ),
            )
            BackupDestination.MEGA -> base.copy(
                mega = MegaConfig(email = o.str("user"), folder = folder.ifEmpty { MegaConfig.DEFAULT_FOLDER }),
            )
            BackupDestination.ICLOUD -> base.copy(
                icloud = ICloudConfig(
                    appleId = o.str("user"),
                    password = o.str("password"),
                    folder = folder.ifEmpty { ICloudConfig.DEFAULT_FOLDER },
                ),
            )
            BackupDestination.PROTON -> base.copy(
                proton = ProtonConfig(user = o.str("user"), folder = folder.ifEmpty { ProtonConfig.DEFAULT_FOLDER }),
            )
        }
    }

    /** [folder], or none when it is this app's own default name for one. */
    private fun ownFolder(folder: String, default: String): String = folder.trim().takeIf { it != default }.orEmpty()

    private fun JsonObject.str(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

    private fun JsonObject.int(key: String, default: Int): Int =
        runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull() ?: default

    private fun JsonObject.flag(key: String, default: Boolean): Boolean =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: default
}

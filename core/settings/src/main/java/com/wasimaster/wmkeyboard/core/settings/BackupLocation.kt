package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sink.ProfileCode
import java.security.SecureRandom
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * One place automatic backups go, and sync reads and writes.
 *
 * The user can have any number, of any type: two WebDAV servers, a Dropbox
 * and a folder on the SD card at once. Every enabled, configured location
 * gets every backup.
 *
 * All of a location's fields live together in one stored list rather than in
 * one preference per field, because the list has no fixed length. The list is
 * a single preference, [SettingsBackup.AUTO_BACKUP_LOCATIONS], named in
 * [SettingsBackup.SECRET_KEYS] because it holds passwords and refresh tokens.
 * Sync never carries it (see
 * [com.wasimaster.wmkeyboard.core.settings.sync.SyncKeys]): where this phone
 * backs up is this phone's business.
 */
data class BackupLocation(
    /** Eight hex characters, stable for the life of the location. */
    val id: String,
    val type: BackupDestination,
    /** What the user called it. Empty means the screen names it after [type]. */
    val name: String = "",
    /**
     * Paused locations keep their settings and get nothing. No longer offered
     * on the screen, where [backup] and the sync list say where each thing
     * goes; kept so a stored `false` still means what it meant.
     */
    val enabled: Boolean = true,
    /** Whether automatic backups go here. Ticked on the Automatic backup screen. */
    val backup: Boolean = true,
    /** [BackupDestination.FOLDER]: a persisted tree URI. See [AutoBackupSettings.folderUri]. */
    val folderUri: String = "",
    val webDavUrl: String = "",
    val webDavUser: String = "",
    val webDavPassword: String = "",
    /** A [WebDavPreset] id, for the screen. [webDavUrl] is what the sink reads. */
    val webDavPreset: String = "",
    /** The preset's `{server}` part. */
    val webDavServer: String = "",
    /** The preset's `{folder}` part. */
    val webDavFolder: String = "",
    val s3: S3Config = S3Config(),
    val ftp: FtpConfig = FtpConfig(),
    val sftp: SftpConfig = SftpConfig(),
    val smb: SmbConfig = SmbConfig(),
    val git: GitConfig = GitConfig(),
    val imap: ImapConfig = ImapConfig(),
    /** [BackupDestination.DRIVE]: the hidden app folder, or a visible one. */
    val driveSpace: DriveSpace = DriveSpace.APP_DATA,
    /** [DriveSpace.FOLDER]: the folder's path from the top of My Drive. */
    val driveFolder: String = DriveSpace.DEFAULT_FOLDER,
    /** Dropbox or OneDrive refresh token. Empty means signed out. */
    val refreshToken: String = "",
    /** [BackupDestination.NEARBY]: the paired device's KDE Connect id. */
    val nearbyDeviceId: String = "",
    /**
     * [BackupDestination.PROFILE]: the pairing code, as typed. The copy in the
     * other profile holds the same one. See
     * [com.wasimaster.wmkeyboard.core.settings.sink.ProfileCode].
     */
    val profileCode: String = "",
    val box: BoxConfig = BoxConfig(),
    val pcloud: PCloudConfig = PCloudConfig(),
    /** [BackupDestination.SYNOLOGY] and [BackupDestination.QNAP] both. */
    val nas: NasConfig = NasConfig(),
    val nfs: NfsConfig = NfsConfig(),
    val mega: MegaConfig = MegaConfig(),
    val icloud: ICloudConfig = ICloudConfig(),
    val proton: ProtonConfig = ProtonConfig(),
) {

    /**
     * Whether this location has everything it needs to be tried. The same
     * cheap question [destinationConfigured] asks of the single destination
     * this replaced; see there.
     */
    val configured: Boolean
        get() = when (type) {
            BackupDestination.FOLDER -> folderUri.isNotEmpty()
            // No user name is a server that asks for none: Taildrive, or an
            // open share on the home network. One that does ask says so.
            BackupDestination.WEBDAV -> webDavUrl.isNotEmpty()
            BackupDestination.DRIVE -> true
            BackupDestination.S3 ->
                s3.bucket.isNotEmpty() && s3.accessKeyId.isNotEmpty() && s3.secretAccessKey.isNotEmpty()
            BackupDestination.DROPBOX, BackupDestination.ONEDRIVE -> refreshToken.isNotEmpty()
            BackupDestination.FTP -> ftp.host.isNotEmpty() && ftp.user.isNotEmpty()
            BackupDestination.SFTP -> sftp.configured
            BackupDestination.SMB -> smb.configured
            BackupDestination.GIT -> git.configured
            BackupDestination.IMAP -> imap.configured
            BackupDestination.NEARBY -> nearbyDeviceId.isNotEmpty()
            BackupDestination.PROFILE -> ProfileCode.normalize(profileCode) != null
            BackupDestination.BOX -> refreshToken.isNotEmpty()
            BackupDestination.PCLOUD -> pcloud.configured
            BackupDestination.SYNOLOGY, BackupDestination.QNAP -> nas.configured
            BackupDestination.NFS -> nfs.configured
            BackupDestination.MEGA -> mega.configured
            BackupDestination.ICLOUD -> icloud.configured
            BackupDestination.PROTON -> proton.configured
        }

    /** Enabled and configured: what a run actually writes to. */
    val active: Boolean get() = enabled && configured

    companion object {

        private const val ID_BYTES = 4

        /**
         * The id of the location made from the old single destination. Fixed
         * rather than random: until the list is first saved it is rebuilt on
         * every read, and a new id each time would orphan its status.
         */
        const val LEGACY_ID = "00000001"

        fun newId(): String =
            ByteArray(ID_BYTES).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        private val json = Json { ignoreUnknownKeys = true }

        /** For the sections that are [kotlinx.serialization.Serializable] classes: see CloudConfigs.kt. */
        private val sections = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        private fun <T> JsonObject.serialized(key: String, serializer: KSerializer<T>, default: T): T =
            section(key)?.let { runCatching { sections.decodeFromJsonElement(serializer, it) }.getOrNull() } ?: default

        /** The stored list, or empty for anything that does not parse. */
        fun decodeList(raw: String?): List<BackupLocation> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
            return array.mapNotNull { runCatching { decode(it.jsonObject) }.getOrNull() }
        }

        fun encodeList(locations: List<BackupLocation>): String =
            JsonArray(locations.map(::encode)).toString()

        private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

        private fun JsonObject.flag(key: String, default: Boolean): Boolean =
            this[key]?.jsonPrimitive?.booleanOrNull ?: default

        private fun JsonObject.int(key: String, default: Int): Int = this[key]?.jsonPrimitive?.intOrNull ?: default

        private fun JsonObject.section(key: String): JsonObject? = runCatching { this[key]?.jsonObject }.getOrNull()

        private fun decode(o: JsonObject): BackupLocation? {
            val type = BackupDestination.entries.firstOrNull { it.id == o.str("type") } ?: return null
            val id = o.str("id").ifEmpty { return null }
            val s3 = o["s3"]?.jsonObject
            val ftp = o["ftp"]?.jsonObject
            val sftp = o.section("sftp")
            val smb = o.section("smb")
            val git = o.section("git")
            val imap = o.section("imap")
            return BackupLocation(
                id = id,
                type = type,
                name = o.str("name"),
                enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                backup = o["backup"]?.jsonPrimitive?.booleanOrNull ?: true,
                folderUri = o.str("folderUri"),
                webDavUrl = o.str("webDavUrl"),
                webDavUser = o.str("webDavUser"),
                webDavPassword = o.str("webDavPassword"),
                webDavPreset = o.str("webDavPreset"),
                webDavServer = o.str("webDavServer"),
                webDavFolder = o.str("webDavFolder"),
                s3 = if (s3 == null) {
                    S3Config()
                } else {
                    S3Config(
                        endpoint = s3.str("endpoint"),
                        region = s3.str("region").ifEmpty { S3Config().region },
                        bucket = s3.str("bucket"),
                        prefix = s3.str("prefix"),
                        accessKeyId = s3.str("accessKeyId"),
                        secretAccessKey = s3.str("secretAccessKey"),
                        pathStyle = s3["pathStyle"]?.jsonPrimitive?.booleanOrNull ?: false,
                        preset = s3.str("preset"),
                        account = s3.str("account"),
                    )
                },
                ftp = if (ftp == null) {
                    FtpConfig()
                } else {
                    FtpConfig(
                        host = ftp.str("host"),
                        port = ftp["port"]?.jsonPrimitive?.intOrNull ?: FtpConfig().port,
                        user = ftp.str("user"),
                        password = ftp.str("password"),
                        path = ftp.str("path"),
                        secure = ftp["secure"]?.jsonPrimitive?.booleanOrNull ?: true,
                    )
                },
                sftp = sftp?.let {
                    SftpConfig(
                        host = it.str("host"),
                        port = it.int("port", SftpConfig().port),
                        user = it.str("user"),
                        password = it.str("password"),
                        privateKey = it.str("privateKey"),
                        keyPassphrase = it.str("keyPassphrase"),
                        path = it.str("path"),
                        hostKey = it.str("hostKey"),
                        legacyAlgorithms = it.flag("legacy", false),
                    )
                } ?: SftpConfig(),
                smb = smb?.let {
                    SmbConfig(
                        host = it.str("host"),
                        port = it.int("port", SmbConfig().port),
                        share = it.str("share"),
                        path = it.str("path"),
                        domain = it.str("domain"),
                        user = it.str("user"),
                        password = it.str("password"),
                        encrypt = it.flag("encrypt", true),
                    )
                } ?: SmbConfig(),
                git = git?.let {
                    GitConfig(
                        provider = GitProvider.of(it.str("provider")),
                        server = it.str("server"),
                        repository = it.str("repository"),
                        branch = it.str("branch"),
                        path = it.str("path"),
                        token = it.str("token"),
                        authorName = it.str("authorName"),
                        authorEmail = it.str("authorEmail"),
                        message = it.str("message"),
                        skipCi = it.flag("skipCi", true),
                        allowPublic = it.flag("allowPublic", false),
                    )
                } ?: GitConfig(),
                imap = imap?.let {
                    val security = ImapSecurity.of(it.str("security"))
                    ImapConfig(
                        host = it.str("host"),
                        port = it.int("port", security.defaultPort),
                        security = security,
                        user = it.str("user"),
                        password = it.str("password"),
                        mailbox = it.str("mailbox").ifEmpty { ImapConfig.DEFAULT_MAILBOX },
                    )
                } ?: ImapConfig(),
                driveSpace = DriveSpace.of(o.str("driveSpace")),
                driveFolder = o.str("driveFolder").ifEmpty { DriveSpace.DEFAULT_FOLDER },
                refreshToken = o.str("refreshToken"),
                nearbyDeviceId = o.str("nearbyDeviceId"),
                profileCode = o.str("profileCode"),
                box = o.serialized("box", BoxConfig.serializer(), BoxConfig()),
                pcloud = o.serialized("pcloud", PCloudConfig.serializer(), PCloudConfig()),
                nas = o.serialized("nas", NasConfig.serializer(), NasConfig()),
                nfs = o.serialized("nfs", NfsConfig.serializer(), NfsConfig()),
                mega = o.serialized("mega", MegaConfig.serializer(), MegaConfig()),
                icloud = o.serialized("icloud", ICloudConfig.serializer(), ICloudConfig()),
                proton = o.serialized("proton", ProtonConfig.serializer(), ProtonConfig()),
            )
        }

        private fun encode(l: BackupLocation): JsonObject = buildJsonObject {
            put("id", JsonPrimitive(l.id))
            put("type", JsonPrimitive(l.type.id))
            if (l.name.isNotEmpty()) put("name", JsonPrimitive(l.name))
            put("enabled", JsonPrimitive(l.enabled))
            put("backup", JsonPrimitive(l.backup))
            when (l.type) {
                BackupDestination.FOLDER -> put("folderUri", JsonPrimitive(l.folderUri))
                BackupDestination.WEBDAV -> {
                    put("webDavUrl", JsonPrimitive(l.webDavUrl))
                    put("webDavUser", JsonPrimitive(l.webDavUser))
                    put("webDavPassword", JsonPrimitive(l.webDavPassword))
                    if (l.webDavPreset.isNotEmpty()) {
                        put("webDavPreset", JsonPrimitive(l.webDavPreset))
                        put("webDavServer", JsonPrimitive(l.webDavServer))
                        put("webDavFolder", JsonPrimitive(l.webDavFolder))
                    }
                }
                BackupDestination.S3 -> put(
                    "s3",
                    buildJsonObject {
                        put("endpoint", JsonPrimitive(l.s3.endpoint))
                        put("region", JsonPrimitive(l.s3.region))
                        put("bucket", JsonPrimitive(l.s3.bucket))
                        put("prefix", JsonPrimitive(l.s3.prefix))
                        put("accessKeyId", JsonPrimitive(l.s3.accessKeyId))
                        put("secretAccessKey", JsonPrimitive(l.s3.secretAccessKey))
                        put("pathStyle", JsonPrimitive(l.s3.pathStyle))
                        if (l.s3.preset.isNotEmpty()) {
                            put("preset", JsonPrimitive(l.s3.preset))
                            put("account", JsonPrimitive(l.s3.account))
                        }
                    },
                )
                BackupDestination.FTP -> put(
                    "ftp",
                    buildJsonObject {
                        put("host", JsonPrimitive(l.ftp.host))
                        put("port", JsonPrimitive(l.ftp.port))
                        put("user", JsonPrimitive(l.ftp.user))
                        put("password", JsonPrimitive(l.ftp.password))
                        put("path", JsonPrimitive(l.ftp.path))
                        put("secure", JsonPrimitive(l.ftp.secure))
                    },
                )
                BackupDestination.SFTP -> put(
                    "sftp",
                    buildJsonObject {
                        put("host", JsonPrimitive(l.sftp.host))
                        put("port", JsonPrimitive(l.sftp.port))
                        put("user", JsonPrimitive(l.sftp.user))
                        put("password", JsonPrimitive(l.sftp.password))
                        put("privateKey", JsonPrimitive(l.sftp.privateKey))
                        put("keyPassphrase", JsonPrimitive(l.sftp.keyPassphrase))
                        put("path", JsonPrimitive(l.sftp.path))
                        put("hostKey", JsonPrimitive(l.sftp.hostKey))
                        put("legacy", JsonPrimitive(l.sftp.legacyAlgorithms))
                    },
                )
                BackupDestination.SMB -> put(
                    "smb",
                    buildJsonObject {
                        put("host", JsonPrimitive(l.smb.host))
                        put("port", JsonPrimitive(l.smb.port))
                        put("share", JsonPrimitive(l.smb.share))
                        put("path", JsonPrimitive(l.smb.path))
                        put("domain", JsonPrimitive(l.smb.domain))
                        put("user", JsonPrimitive(l.smb.user))
                        put("password", JsonPrimitive(l.smb.password))
                        put("encrypt", JsonPrimitive(l.smb.encrypt))
                    },
                )
                BackupDestination.GIT -> put(
                    "git",
                    buildJsonObject {
                        put("provider", JsonPrimitive(l.git.provider.id))
                        put("server", JsonPrimitive(l.git.server))
                        put("repository", JsonPrimitive(l.git.repository))
                        put("branch", JsonPrimitive(l.git.branch))
                        put("path", JsonPrimitive(l.git.path))
                        put("token", JsonPrimitive(l.git.token))
                        put("authorName", JsonPrimitive(l.git.authorName))
                        put("authorEmail", JsonPrimitive(l.git.authorEmail))
                        put("message", JsonPrimitive(l.git.message))
                        put("skipCi", JsonPrimitive(l.git.skipCi))
                        put("allowPublic", JsonPrimitive(l.git.allowPublic))
                    },
                )
                BackupDestination.IMAP -> put(
                    "imap",
                    buildJsonObject {
                        put("host", JsonPrimitive(l.imap.host))
                        put("port", JsonPrimitive(l.imap.port))
                        put("security", JsonPrimitive(l.imap.security.id))
                        put("user", JsonPrimitive(l.imap.user))
                        put("password", JsonPrimitive(l.imap.password))
                        put("mailbox", JsonPrimitive(l.imap.mailbox))
                    },
                )
                BackupDestination.DROPBOX, BackupDestination.ONEDRIVE ->
                    put("refreshToken", JsonPrimitive(l.refreshToken))
                BackupDestination.BOX -> {
                    put("refreshToken", JsonPrimitive(l.refreshToken))
                    put("box", sections.encodeToJsonElement(BoxConfig.serializer(), l.box))
                }
                BackupDestination.PCLOUD -> put("pcloud", sections.encodeToJsonElement(PCloudConfig.serializer(), l.pcloud))
                BackupDestination.SYNOLOGY, BackupDestination.QNAP ->
                    put("nas", sections.encodeToJsonElement(NasConfig.serializer(), l.nas))
                BackupDestination.NFS -> put("nfs", sections.encodeToJsonElement(NfsConfig.serializer(), l.nfs))
                BackupDestination.MEGA -> put("mega", sections.encodeToJsonElement(MegaConfig.serializer(), l.mega))
                BackupDestination.ICLOUD -> put("icloud", sections.encodeToJsonElement(ICloudConfig.serializer(), l.icloud))
                BackupDestination.PROTON -> put("proton", sections.encodeToJsonElement(ProtonConfig.serializer(), l.proton))
                BackupDestination.NEARBY -> put("nearbyDeviceId", JsonPrimitive(l.nearbyDeviceId))
                BackupDestination.PROFILE -> put("profileCode", JsonPrimitive(l.profileCode))
                BackupDestination.DRIVE -> {
                    put("driveSpace", JsonPrimitive(l.driveSpace.id))
                    if (l.driveSpace == DriveSpace.FOLDER) put("driveFolder", JsonPrimitive(l.driveFolder))
                }
            }
        }

        /**
         * The location the single-destination settings described, for the
         * one-time move to a list. Null when that destination was never set
         * up, so an untouched install starts with no locations rather than
         * an empty folder nobody chose.
         */
        fun fromLegacy(auto: AutoBackupSettings): BackupLocation? {
            if (!auto.destinationConfigured) return null
            return BackupLocation(
                id = LEGACY_ID,
                type = auto.destination,
                folderUri = auto.folderUri,
                webDavUrl = auto.webDavUrl,
                webDavUser = auto.webDavUser,
                webDavPassword = auto.webDavPassword,
                s3 = auto.s3,
                ftp = auto.ftp,
                refreshToken = when (auto.destination) {
                    BackupDestination.DROPBOX -> auto.dropboxRefreshToken
                    BackupDestination.ONEDRIVE -> auto.oneDriveRefreshToken
                    else -> ""
                },
            )
        }
    }
}

/**
 * How the last backup and the last sync went at one location. Per install,
 * never exported: another phone's run record means nothing here.
 */
data class LocationStatus(
    val backupAtMs: Long = 0L,
    /** A `SinkError` name, or empty. */
    val backupError: String = "",
    val syncAtMs: Long = 0L,
    val syncError: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decodeMap(raw: String?): Map<String, LocationStatus> {
            if (raw.isNullOrBlank()) return emptyMap()
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
            return root.mapValues { (_, v) ->
                val o = v.jsonObject
                LocationStatus(
                    backupAtMs = o["b"]?.jsonPrimitive?.longOrNull ?: 0L,
                    backupError = o["be"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    syncAtMs = o["s"]?.jsonPrimitive?.longOrNull ?: 0L,
                    syncError = o["se"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }

        fun encodeMap(map: Map<String, LocationStatus>): String = JsonObject(
            map.mapValues { (_, s) ->
                buildJsonObject {
                    put("b", JsonPrimitive(s.backupAtMs))
                    put("be", JsonPrimitive(s.backupError))
                    put("s", JsonPrimitive(s.syncAtMs))
                    put("se", JsonPrimitive(s.syncError))
                }
            },
        ).toString()
    }
}

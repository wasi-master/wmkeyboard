package com.wasimaster.wmkeyboard.core.tools.privatemode

import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Privatemode AI without its proxy (#1145): what `privatemode-proxy` does on
 * the user's own machine, done on the phone instead.
 *
 * Before the first request, and again every 45 minutes:
 *
 * 1. the deployment's manifest is fetched from Privatemode's CDN;
 * 2. the API is asked to attest itself with a fresh nonce, and the answer
 *    has to carry exactly that manifest, as the Go client insists;
 * 3. the phone sends a fresh MLKEM768-X25519 public key to the secret
 *    service, checks that the answer is signed by a certificate of the mesh
 *    CA named in step 2, and derives the shared inference secret from it.
 *
 * Every chat request is then encrypted end to end with that secret
 * ([EncryptedBody]), and the streamed answer opened as it arrives.
 *
 * What is NOT done, and is the reason this never shows as "verified": step 2
 * does not check the hardware report against AMD's or Intel's certificates,
 * nor the deployment's measurements against the manifest. That is the
 * Contrast SDK's validator (SEV-SNP and TDX report verification, KDS and PCS
 * collateral, revocation lists), which is not ported. So the mesh CA is the
 * one the API says it has: the encryption keeps the text away from
 * everything between the phone and the service that holds that CA, but a
 * service that lied about its attestation would not be caught here. The
 * proxy does check it; [Session.check] says which of the checks ran.
 */
internal object PrivatemodeClient {

    const val API_BASE = "https://api.privatemode.ai"
    private const val MANIFEST_URL = "https://cdn.confidential.cloud/privatemode/v2/manifest.json"

    /**
     * The client version the API is told. From v1.47 the server checks the
     * token estimate header, from v1.61 it wants the NVIDIA OCSP policy inside
     * the encrypted request; both are sent, matching the proxy this follows.
     */
    const val CLIENT_VERSION = "v1.62.0"

    /** The secret service keeps a secret for an hour; renew it before that, as the proxy does. */
    private const val SECRET_LIFETIME_MS = 45 * 60_000L

    private const val NONCE_SIZE = 32
    private const val CHARS_PER_TOKEN = 4

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    /**
     * One cache salt for the life of the process, as the Go client keeps one
     * per client: the service shards its prompt cache by it, so repeated
     * requests from this phone stay on warm caches without sharing any with
     * other users. At least 32 characters, which the service requires.
     */
    private val cacheSalt: String by lazy {
        ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }

    /** What the phone checked on the way to its secret. [hardwareVerified] is always false: see the class comment. */
    data class Check(
        /** The attested deployment runs the manifest Privatemode publishes. */
        val manifestMatches: Boolean,
        /** The hardware report's data field binds this request's nonce, the manifest and the mesh CA. Not its signature. */
        val reportBound: Boolean,
        /** The secret was signed by a certificate of that mesh CA. */
        val secretSigned: Boolean,
    ) {
        val hardwareVerified: Boolean get() = false
    }

    class Session internal constructor(
        val apiKey: String,
        val id: String,
        internal val secret: ByteArray,
        val expiresAt: Long,
        val check: Check,
    )

    @Volatile
    private var session: Session? = null

    /** The last session's checks, for the provider's screen. Null before the first request. */
    val lastCheck: Check? get() = session?.check

    private val lock = Any()

    /** A live session for [apiKey], set up anew when there is none or it is old. Blocking. */
    fun session(apiKey: String, source: NetSource, now: Long = System.currentTimeMillis()): Session = synchronized(lock) {
        session?.takeIf { it.apiKey == apiKey && now < it.expiresAt }?.let { return it }
        open(apiKey, source, now).also { session = it }
    }

    private fun forget() = synchronized(lock) { session = null }

    private fun open(apiKey: String, source: NetSource, now: Long): Session {
        val manifest = ToolHttp.bytes(MANIFEST_URL + "?t=" + now, source = source, route = "/privatemode/v2/manifest.json").body
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val attested = attest(apiKey, nonce, source)
        val manifests = attested.manifests
        if (manifests.size != 1 || !manifests[0].contentEquals(manifest)) {
            throw IOException("Privatemode's attested deployment does not match its published manifest")
        }
        val meshCaPem = attested.meshCa
        val meshCa = PrivatemodeCrypto.parsePemCertificate(meshCaPem)
        val reportBound = attested.rawReport.contains(reportData(nonce, manifests, attested.rootCa, meshCaPem))

        val keys = HybridKeyPair.generate(random)
        val answer = postJson(
            "$API_BASE/privatemode/v1/secret",
            buildJsonObject { put("PublicKey", Base64.getEncoder().encodeToString(keys.publicKey)) }.toString(),
            apiKey,
            source,
        )
        val encapsulated = answer.bytes("EncapsulatedKey")
        if (!PrivatemodeCrypto.verifySecretResponse(
                meshCa, answer.bytes("MeshCert"), keys.publicKey, encapsulated, answer.bytes("Signature"),
            )
        ) {
            throw IOException("Privatemode's secret was not signed by its attested mesh CA")
        }
        val secret = PrivatemodeCrypto.exportSecret(keys.decapsulate(encapsulated))
        return Session(
            apiKey = apiKey,
            id = PrivatemodeCrypto.secretId(keys.publicKey),
            secret = secret,
            expiresAt = now + SECRET_LIFETIME_MS,
            check = Check(manifestMatches = true, reportBound = reportBound, secretSigned = true),
        )
    }

    /** The parts of the coordinator's attestation answer the phone uses. */
    internal class Attested(
        val manifests: List<ByteArray>,
        val rootCa: ByteArray,
        val meshCa: ByteArray,
        val rawReport: ByteArray,
    )

    private fun attest(apiKey: String, nonce: ByteArray, source: NetSource): Attested {
        val answer = postJson(
            "$API_BASE/privatemode/v1/attest",
            buildJsonObject { put("Nonce", Base64.getEncoder().encodeToString(nonce)) }.toString(),
            apiKey,
            source,
        )
        return parseAttestation(answer.bytes("AttestationDoc"))
    }

    /** Contrast's `AttestationResponse`: byte fields are base64, as Go's JSON writes them. */
    internal fun parseAttestation(document: ByteArray): Attested {
        val root = json.parseToJsonElement(document.decodeToString()) as? JsonObject
            ?: throw IOException("Malformed attestation document")
        val manifests = (root["manifests"] as? JsonArray).orEmpty().map {
            Base64.getDecoder().decode((it as JsonPrimitive).content)
        }
        return Attested(manifests, root.bytes("root_ca"), root.bytes("mesh_ca"), root.bytes("raw_attestation_doc"))
    }

    /**
     * The report data the coordinator puts in its hardware report, as
     * Contrast's `ConstructReportData` builds it: SHA-256 of the nonce, the
     * digest of the last manifest transition, and the digests of the root and
     * mesh CAs, padded to 64 bytes.
     */
    internal fun reportData(nonce: ByteArray, manifests: List<ByteArray>, rootCa: ByteArray, meshCa: ByteArray): ByteArray {
        var transition = ByteArray(32)
        for (manifest in manifests) {
            transition = PrivatemodeCrypto.sha256(PrivatemodeCrypto.sha256(manifest) + transition)
        }
        val digest = PrivatemodeCrypto.sha256(
            nonce + transition + PrivatemodeCrypto.sha256(rootCa) + PrivatemodeCrypto.sha256(meshCa),
        )
        return digest + ByteArray(32)
    }

    private fun postJson(url: String, body: String, apiKey: String, source: NetSource): JsonObject {
        val text = ToolHttp.postJson(
            url,
            body,
            timeoutMs = 30_000,
            headers = mapOf("Authorization" to "Bearer $apiKey", "Privatemode-Version" to CLIENT_VERSION),
            source = source,
            route = NetLog.pathOf(url),
        )
        return json.parseToJsonElement(text) as? JsonObject ?: throw IOException("Malformed answer from Privatemode")
    }

    /**
     * The request body as the service wants it: [body] with the cache salt
     * added, and the token estimate the service recomputes from the
     * decrypted body and refuses the request over if they differ. The
     * estimate is the byte length of the `tools` and `messages` JSON as
     * they appear in the body, divided by four (their `EstimateContentTokens`
     * for chat). Pictures are never sent here, so their placeholder rule
     * does not arise.
     */
    internal fun prepare(body: String): Pair<String, Long> {
        val parsed = json.parseToJsonElement(body) as JsonObject
        val salted = if (parsed.containsKey("cache_salt")) parsed else JsonObject(parsed + ("cache_salt" to JsonPrimitive(cacheSalt)))
        val counted = listOfNotNull(salted["tools"], salted["messages"]).sumOf { it.toString().toByteArray().size.toLong() }
        return salted.toString() to counted / CHARS_PER_TOKEN
    }

    /**
     * One encrypted chat-completions request, its answer handed to [onLine]
     * a line at a time as it is opened, the way [ToolHttp.postJsonStream]
     * hands a plain one. Retries once with a new secret when the service has
     * forgotten the old one, which it does after an hour or a restart.
     */
    @Suppress("LongParameterList")
    fun streamChat(
        apiKey: String,
        model: String,
        body: String,
        source: NetSource,
        timeoutMs: Int,
        onRequestSent: () -> Unit,
        onLine: (String) -> Boolean,
    ) {
        try {
            streamOnce(session(apiKey, source), model, body, source, timeoutMs, onRequestSent, onLine)
        } catch (e: ToolHttpException) {
            if (e.apiMessage?.contains(NO_SECRET) != true) throw e
            forget()
            streamOnce(session(apiKey, source), model, body, source, timeoutMs, onRequestSent, onLine)
        }
    }

    /** What the service says when it no longer holds the secret a request names. */
    private const val NO_SECRET = "no secret for ID"

    @Suppress("LongParameterList")
    private fun streamOnce(
        session: Session,
        model: String,
        body: String,
        source: NetSource,
        timeoutMs: Int,
        onRequestSent: () -> Unit,
        onLine: (String) -> Boolean,
    ) {
        val (plain, tokens) = prepare(body)
        val requester = Oae.Requester.start(session.secret, random)
        // Accepted only good NVIDIA certificate statuses, from now: the
        // proxy's default policy, carried inside the encryption so nothing
        // on the way can loosen it.
        val policy = "rules=allow-good; revocation-time-not-before=" + System.currentTimeMillis() / 1000
        val encrypted = EncryptedBody.encryptRequest(
            requester.sealer, plain.toByteArray(), "application/json",
            mapOf("Privatemode-NVIDIA-OCSP-Policy" to policy),
        )
        val url = "$API_BASE/v1/chat/completions"
        ToolHttp.binaryExchange(
            url = url,
            body = encrypted,
            contentType = EncryptedBody.CONTENT_TYPE,
            headers = mapOf(
                "Authorization" to "Bearer ${session.apiKey}",
                "Privatemode-Version" to CLIENT_VERSION,
                "Privatemode-Secret-ID" to session.id,
                "Privatemode-Target-Model" to model,
                "Privatemode-Content-Length-Tokens" to tokens.toString(),
                "Privatemode-User-Request-ID" to "wmkeyboard_" + UUID.randomUUID(),
                EncryptedBody.REQUEST_HEADER to EncryptedBody.encodeHeader(requester.salt),
            ),
            wantHeaders = setOf(EncryptedBody.RESPONSE_HEADER),
            timeoutMs = timeoutMs,
            onRequestSent = onRequestSent,
            source = source,
            route = NetLog.pathOf(url),
        ) { exchange ->
            val input = exchange.body
            val encryptedReply = exchange.contentType == EncryptedBody.CONTENT_TYPE
            if (exchange.status !in 200..299) {
                val text = when {
                    input == null -> null
                    encryptedReply -> runCatching {
                        val opener = requester.accept(EncryptedBody.decodeHeader(exchange.headers[EncryptedBody.RESPONSE_HEADER]))
                        EncryptedBody.Decrypting(input, opener).readBytes().decodeToString()
                    }.getOrNull()
                    else -> input.readBytes().decodeToString()
                }
                throw ToolHttp.httpFailure(exchange.status, text)
            }
            // An answer that came back in the clear means something on the
            // way is not the service this was encrypted for. Refuse it.
            if (!encryptedReply || input == null) throw IOException("Privatemode answered without encryption")
            val opener = requester.accept(EncryptedBody.decodeHeader(exchange.headers[EncryptedBody.RESPONSE_HEADER]))
            EncryptedBody.Decrypting(input, opener).bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!onLine(line)) break
                }
            }
        }
    }

    /** The models the key may use: their list is not encrypted, as in their own client. */
    fun listModelsUrl(): String = "$API_BASE/v1/models"

    private fun JsonObject.bytes(key: String): ByteArray =
        (this[key] as? JsonPrimitive)?.content?.let { Base64.getDecoder().decode(it) }
            ?: throw IOException("Privatemode's answer has no $key")

    /** Whether [needle] occurs anywhere in this array. */
    private fun ByteArray.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }
}

package com.wasimaster.wmkeyboard.core.tools.privatemode

import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.digests.SHAKEDigest
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The client half of Privatemode's end-to-end encryption, ported from their
 * open-source proxy (github.com/edgelesssys/privatemode-public, MIT): the
 * pieces `privatemode-proxy` runs before a request leaves the user's machine.
 *
 * - [HybridKeyPair]: the key the phone offers in the secret exchange,
 *   MLKEM768-X25519 ("X-Wing", HPKE KEM 0x647a from draft-ietf-hpke-pq), the
 *   same KEM Go's `crypto/hpke` implements, and [exportSecret], HPKE's
 *   export-only key schedule over it. Together they give the 32-byte
 *   inference secret both ends share.
 * - [Oae]: "online authenticated encryption", their RFC-29. Each request
 *   and response is a stream of AES-256-GCM messages keyed from that secret
 *   by HKDF and bound to their position, so a reordered, repeated or cut-off
 *   stream fails to open.
 * - [EncryptedBody]: their RFC-28 wire format, length-prefixed frames whose
 *   first one names the plaintext's content type.
 * - [verifySecretResponse]: the check that the secret came from a service
 *   holding a certificate of the deployment's mesh CA.
 *
 * Pure JVM plus Bouncy Castle (ML-KEM, X25519, SHA-3), so the unit tests run
 * it against vectors Go's own implementation produced.
 */
internal object PrivatemodeCrypto {

    /** Bytes of the shared inference secret. */
    const val SECRET_SIZE = 32

    /** HPKE's KEM id for MLKEM768-X25519. */
    private const val KEM_ID = 0x647a
    private const val KDF_HKDF_SHA256 = 0x0001
    private const val AEAD_EXPORT_ONLY = 0xFFFF

    private val SUITE_ID = "HPKE".toByteArray() + be16(KEM_ID) + be16(KDF_HKDF_SHA256) + be16(AEAD_EXPORT_ONLY)
    private val HPKE_V1 = "HPKE-v1".toByteArray()
    private const val HASH_SIZE = 32

    /**
     * HPKE base mode with an empty info, then `Export("", 32)`: what the Go
     * proxy's `hpke.NewRecipient(enc, priv, HKDFSHA256(), ExportOnly(), nil)`
     * followed by `recipient.Export("", 32)` computes from the KEM's shared
     * secret. The export-only AEAD has no key or nonce, so only the exporter
     * secret is derived.
     */
    fun exportSecret(kemSharedSecret: ByteArray): ByteArray {
        val pskIdHash = labeledExtract(ByteArray(0), "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(ByteArray(0), "info_hash", ByteArray(0))
        val context = byteArrayOf(0) + pskIdHash + infoHash
        val secret = labeledExtract(kemSharedSecret, "secret", ByteArray(0))
        val exporter = labeledExpand(secret, "exp", context, HASH_SIZE)
        return labeledExpand(exporter, "sec", ByteArray(0), SECRET_SIZE)
    }

    /**
     * The id the service files a secret under: base64 of SHA-256 over the
     * public key the phone sent (`secretexchange.ID`).
     */
    fun secretId(requestPublicKey: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(sha256(requestPublicKey))

    /**
     * Checks the secret service's answer the way the Go client does: the
     * certificate it sent chains to [meshCa], and its key signed SHA-256 of
     * the phone's public key followed by the encapsulated key. False on any
     * failure, including a certificate outside its dates.
     *
     * The digest is signed as it is (Go's `ecdsa.VerifyASN1` over a hash),
     * which is why this is `NONEwithECDSA` rather than `SHA256withECDSA`.
     */
    fun verifySecretResponse(
        meshCa: X509Certificate,
        meshCertDer: ByteArray,
        requestPublicKey: ByteArray,
        encapsulatedKey: ByteArray,
        signature: ByteArray,
    ): Boolean = runCatching {
        val leaf = parseCertificate(meshCertDer)
        meshCa.checkValidity()
        leaf.checkValidity()
        leaf.verify(meshCa.publicKey)
        if (leaf.publicKey.algorithm != "EC") return false
        val digest = sha256(requestPublicKey + encapsulatedKey)
        Signature.getInstance("NONEwithECDSA").run {
            initVerify(leaf.publicKey)
            update(digest)
            verify(signature)
        }
    }.getOrDefault(false)

    fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    /** The first certificate in a PEM text. */
    fun parsePemCertificate(pem: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem)) as X509Certificate

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun labeledExtract(salt: ByteArray, label: String, ikm: ByteArray): ByteArray =
        hkdfExtract(salt, HPKE_V1 + SUITE_ID + label.toByteArray() + ikm)

    private fun labeledExpand(prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray =
        hkdfExpand(prk, be16(length) + HPKE_V1 + SUITE_ID + label.toByteArray() + info, length)

    /** HKDF-Extract with SHA-256. An empty salt is HMAC's all-zero key, as RFC 5869 says. */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmac(if (salt.isEmpty()) ByteArray(HASH_SIZE) else salt, ikm)

    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - written)
            System.arraycopy(previous, 0, out, written, take)
            written += take
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    fun be16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

    fun be32(value: Long): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}

/**
 * One MLKEM768-X25519 key pair, from a 32-byte seed the way draft-ietf-hpke-pq
 * (and Go) expand it: SHAKE256 of the seed gives the 64-byte ML-KEM seed and
 * then the 32-byte X25519 scalar. [publicKey] is the ML-KEM encapsulation key
 * followed by the X25519 point, 1216 bytes.
 */
internal class HybridKeyPair private constructor(
    private val mlKem: MLKEMPrivateKeyParameters,
    private val x25519Secret: ByteArray,
    private val x25519Public: ByteArray,
) {
    val publicKey: ByteArray = mlKem.publicKey + x25519Public

    /**
     * The KEM shared secret for [encapsulated] (the ML-KEM ciphertext then the
     * sender's X25519 point): SHA3-256 over both shared secrets, the X25519
     * ciphertext and our X25519 point, then the label. Throws on a malformed
     * or all-zero exchange, as Go does.
     */
    fun decapsulate(encapsulated: ByteArray): ByteArray {
        if (encapsulated.size != ML_KEM_CIPHERTEXT + X25519_SIZE) throw IOException("Wrong encapsulated key size")
        val pqCiphertext = encapsulated.copyOfRange(0, ML_KEM_CIPHERTEXT)
        val curveCiphertext = encapsulated.copyOfRange(ML_KEM_CIPHERTEXT, encapsulated.size)
        val pqSecret = MLKEMExtractor(mlKem).extractSecret(pqCiphertext)
        val curveSecret = ByteArray(X25519_SIZE)
        if (!X25519.calculateAgreement(x25519Secret, 0, curveCiphertext, 0, curveSecret, 0)) {
            throw IOException("Invalid X25519 share")
        }
        val sha3 = SHA3Digest(256)
        for (part in listOf(pqSecret, curveSecret, curveCiphertext, x25519Public, LABEL)) sha3.update(part, 0, part.size)
        return ByteArray(32).also { sha3.doFinal(it, 0) }
    }

    companion object {
        private const val ML_KEM_CIPHERTEXT = 1088
        private const val ML_KEM_SEED = 64
        private const val X25519_SIZE = 32
        private const val SEED_SIZE = 32

        /** X-Wing's label, `\./` then `/^\`. */
        private val LABEL = byteArrayOf(0x5c, 0x2e, 0x2f, 0x2f, 0x5e, 0x5c)

        fun generate(random: SecureRandom = SecureRandom()): HybridKeyPair =
            fromSeed(ByteArray(SEED_SIZE).also(random::nextBytes))

        fun fromSeed(seed: ByteArray): HybridKeyPair {
            require(seed.size == SEED_SIZE) { "Seed must be $SEED_SIZE bytes" }
            val shake = SHAKEDigest(256)
            shake.update(seed, 0, seed.size)
            val expanded = ByteArray(ML_KEM_SEED + X25519_SIZE)
            shake.doFinal(expanded, 0, expanded.size)
            val mlKem = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, expanded.copyOfRange(0, ML_KEM_SEED))
            val curveSecret = expanded.copyOfRange(ML_KEM_SEED, expanded.size)
            val curvePublic = ByteArray(X25519_SIZE)
            X25519.generatePublicKey(curveSecret, 0, curvePublic, 0)
            return HybridKeyPair(mlKem, curveSecret, curvePublic)
        }
    }
}

/**
 * Privatemode's online authenticated encryption (their RFC-29, `oae.go`).
 *
 * The request stream's key comes from HKDF over the shared secret salted
 * with a random 32 bytes the client sends as the request header; the
 * response stream's from the same extract with the responder's own 32 bytes
 * in the info. A sealed message is the final flag, a random 12-byte nonce and
 * the AES-GCM ciphertext with its tag, authenticated against its sequence
 * number and that flag.
 */
internal object Oae {
    const val SALT_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private const val KEY_SIZE = 32

    /** What a sealed message adds to its plaintext: flag, nonce and tag. */
    const val OVERHEAD = 1 + NONCE_SIZE + TAG_BITS / 8

    private val REQUEST_INFO = "privatemode oae request key\u0000".toByteArray()
    private val RESPONSE_INFO = "privatemode oae response key\u0000".toByteArray()

    /** The requester's side of one exchange: [sealer] for the request, [salt] to send, then [accept] the response's. */
    class Requester(secret: ByteArray, val salt: ByteArray) {
        private var prk: ByteArray? = run {
            require(secret.size == PrivatemodeCrypto.SECRET_SIZE) { "Wrong secret size" }
            require(salt.size == SALT_SIZE) { "Wrong salt size" }
            PrivatemodeCrypto.hkdfExtract(salt, secret)
        }
        val sealer = Sealer(PrivatemodeCrypto.hkdfExpand(prk!!, REQUEST_INFO, KEY_SIZE))

        /** The response stream's opener, from the responder's salt. Single use. */
        fun accept(responseSalt: ByteArray): Opener {
            val key = prk ?: throw IOException("Response already accepted")
            prk = null
            if (responseSalt.size != SALT_SIZE) throw IOException("Malformed response header")
            return Opener(PrivatemodeCrypto.hkdfExpand(key, RESPONSE_INFO + responseSalt, KEY_SIZE))
        }

        companion object {
            fun start(secret: ByteArray, random: SecureRandom = SecureRandom()): Requester =
                Requester(secret, ByteArray(SALT_SIZE).also(random::nextBytes))
        }
    }

    /** For tests: the request stream's opener for a known [salt]. */
    internal fun requestOpener(secret: ByteArray, salt: ByteArray): Opener =
        Opener(PrivatemodeCrypto.hkdfExpand(PrivatemodeCrypto.hkdfExtract(salt, secret), REQUEST_INFO, KEY_SIZE))

    class Sealer(private val key: ByteArray, private val random: SecureRandom = SecureRandom()) {
        private var sequence = 0L
        private var closed = false

        fun seal(plaintext: ByteArray, final: Boolean): ByteArray {
            if (closed) throw IOException("OAE stream closed")
            if (sequence >= MAX_MESSAGES) throw IOException("OAE message limit reached")
            val flag: Byte = if (final) 1 else 0
            val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(PrivatemodeCrypto.be32(sequence) + byteArrayOf(flag))
            val sealed = byteArrayOf(flag) + nonce + cipher.doFinal(plaintext)
            sequence++
            if (final) closed = true
            return sealed
        }
    }

    class Opener(private val key: ByteArray) {
        private var sequence = 0L

        /** True once the final message has been opened. */
        var closed = false
            private set

        fun open(sealed: ByteArray): ByteArray {
            if (closed) throw IOException("OAE stream closed")
            if (sequence >= MAX_MESSAGES) throw IOException("OAE message limit reached")
            if (sealed.size < OVERHEAD) throw IOException("Malformed sealed message")
            val flag = sealed[0]
            if (flag != 0.toByte() && flag != 1.toByte()) throw IOException("Malformed sealed message")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 1, NONCE_SIZE))
            cipher.updateAAD(PrivatemodeCrypto.be32(sequence) + byteArrayOf(flag))
            val plain = try {
                cipher.doFinal(sealed, 1 + NONCE_SIZE, sealed.size - 1 - NONCE_SIZE)
            } catch (e: java.security.GeneralSecurityException) {
                throw IOException("OAE message authentication failed", e)
            }
            sequence++
            if (flag == 1.toByte()) closed = true
            return plain
        }
    }

    private const val MAX_MESSAGES = 1L shl 32
}

/**
 * Privatemode's encrypted body (their RFC-28, `encbody`): big-endian
 * uint32-length-prefixed OAE messages. The first carries a small JSON "app
 * header" with the plaintext's content type; a request is that and one final
 * message with the whole body, a streamed response one message per server
 * event and an empty final one.
 */
internal object EncryptedBody {
    const val CONTENT_TYPE = "application/vnd.privatemode.encrypted-body"
    const val REQUEST_HEADER = "Privatemode-OAE-Request-Header"
    const val RESPONSE_HEADER = "Privatemode-OAE-Response-Header"

    /** One frame's ceiling: their unary limit plus overhead. */
    const val MAX_FRAME = 128 * 1024 * 1024 + 64

    /** The whole request body: the app header with [contentType] and [authenticated] metadata, then [plaintext]. */
    fun encryptRequest(
        sealer: Oae.Sealer,
        plaintext: ByteArray,
        contentType: String,
        authenticated: Map<String, String>,
    ): ByteArray {
        val header = kotlinx.serialization.json.buildJsonObject {
            put("content_type", kotlinx.serialization.json.JsonPrimitive(contentType))
            if (authenticated.isNotEmpty()) {
                put(
                    "headers",
                    kotlinx.serialization.json.JsonObject(authenticated.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }),
                )
            }
        }.toString().toByteArray()
        return frame(sealer.seal(header, final = false)) + frame(sealer.seal(plaintext, final = true))
    }

    private fun frame(sealed: ByteArray): ByteArray = PrivatemodeCrypto.be32(sealed.size.toLong()) + sealed

    /** RFC 9651 byte sequence, `:base64:`, which is how the OAE headers travel. */
    fun encodeHeader(raw: ByteArray): String = ":" + java.util.Base64.getEncoder().encodeToString(raw) + ":"

    fun decodeHeader(value: String?): ByteArray {
        val text = value?.trim().orEmpty()
        if (text.length < 2 || text.first() != ':' || text.last() != ':') throw IOException("Malformed OAE header")
        return try {
            java.util.Base64.getDecoder().decode(text.substring(1, text.length - 1))
        } catch (e: IllegalArgumentException) {
            throw IOException("Malformed OAE header", e)
        }
    }

    /**
     * The plaintext of an encrypted body as a stream, opened frame by frame as
     * it arrives so a streamed answer still shows as it is written. Reading
     * past the end of a body that never sent its final message throws: a
     * cut-off answer must not pass for a whole one. [contentType] is the app
     * header's, read when the stream opens.
     */
    class Decrypting(input: InputStream, private val opener: Oae.Opener) : InputStream() {
        private val data = DataInputStream(input)
        private var buffer = ByteArray(0)
        private var position = 0

        val contentType: String

        init {
            val header = nextFrame() ?: throw IOException("Encrypted body has no header")
            contentType = runCatching {
                val root = kotlinx.serialization.json.Json.parseToJsonElement(String(header)) as kotlinx.serialization.json.JsonObject
                (root["content_type"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            }.getOrElse { throw IOException("Malformed encrypted body header", it) }
        }

        private fun nextFrame(): ByteArray? {
            if (opener.closed) return null
            val size = try {
                data.readInt().toLong() and 0xFFFFFFFFL
            } catch (_: EOFException) {
                throw IOException("Encrypted response was cut off")
            }
            if (size > MAX_FRAME) throw IOException("Encrypted frame too large")
            val sealed = ByteArray(size.toInt())
            try {
                data.readFully(sealed)
            } catch (e: EOFException) {
                throw IOException("Encrypted response was cut off", e)
            }
            return opener.open(sealed)
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position >= buffer.size) {
                buffer = nextFrame() ?: return -1
                position = 0
            }
            val take = minOf(len, buffer.size - position)
            System.arraycopy(buffer, position, b, off, take)
            position += take
            return take
        }

        override fun close() = data.close()
    }
}

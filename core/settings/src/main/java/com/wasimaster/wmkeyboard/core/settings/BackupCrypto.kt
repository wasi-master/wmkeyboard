package com.wasimaster.wmkeyboard.core.settings

import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The config bundle, encrypted under a passphrase.
 *
 * A strictly outer layer: [ConfigBackup] stays a JSON codec that knows nothing
 * about this, and the restore path decrypts first and then hands the resulting
 * text to [ConfigBackup.decode] exactly as the plaintext path does.
 *
 * The bundle can carry API keys and `learning/user_lexicon.json`, which is the
 * words the user has typed. Once a backup is written on a timer into a folder
 * that syncs to somebody's cloud, that content leaves the device without anyone
 * watching. This is what makes that defensible.
 *
 * **What it does not protect against.** The passphrase is stored on the device,
 * because an unattended backup has nobody to type it. So this protects the file
 * where it lands — in the cloud folder, in someone else's backup of that
 * folder, on a shared drive — and not against someone holding an unlocked
 * phone. The settings screen says so in those words.
 */
object BackupCrypto {

    /**
     * PBKDF2-HMAC-SHA512 rounds for a new file: OWASP's figure for SHA-512.
     *
     * The loop below is a `Mac` running in the JVM rather than a native one.
     * SHA-512 works in 64-bit words, so on the arm64 cores every supported
     * phone has a round costs about what a SHA-256 one does, and a 2018-era
     * low-end core still gets through this in two to three seconds. A
     * background job can spend that, and a restore screen shows a spinner for
     * it. The number lives in the header, so raising it later does not
     * invalidate old files.
     */
    const val DEFAULT_ITERATIONS = 210_000

    /**
     * A ceiling on what a *file* may ask for.
     *
     * The iteration count is read out of the header before any of it is
     * authenticated — it has to be, since it is what produces the key that does
     * the authenticating. So a hostile file can name a number that would hang
     * the restore for an hour. This is the bound that stops it.
     */
    private const val MAX_ITERATIONS = 2_000_000

    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val KEY_SIZE = 32
    private const val TAG_BITS = 128

    /** What files written before SHA-512 carry. Still read, never written. */
    private const val KDF_PBKDF2_SHA256: Byte = 1
    private const val KDF_PBKDF2_SHA512: Byte = 2
    private const val CIPHER_AES_GCM: Byte = 1
    private const val CONTAINER_VERSION: Byte = 1

    /**
     * The four bytes a file starts with.
     *
     * `WMKB` cannot begin a JSON document, so the import path can tell an
     * encrypted bundle from a plain one by reading four bytes, the same way it
     * already recognises a zip.
     */
    val MAGIC: ByteArray = byteArrayOf('W'.code.toByte(), 'M'.code.toByte(), 'K'.code.toByte(), 'B'.code.toByte())

    /**
     * ```
     * off len  field
     *   0   4  magic 'W' 'M' 'K' 'B'
     *   4   3  'E' 'N' 'C'
     *   7   1  container version
     *   8   1  kdf id: 2 PBKDF2-HMAC-SHA512, 1 PBKDF2-HMAC-SHA256 (older files)
     *   9   1  cipher id
     *  10   4  iterations, big-endian
     *  14   1  salt length
     *  15  16  salt
     *  31   1  nonce length
     *  32  12  nonce
     *  44   n  ciphertext, with the 16-byte GCM tag on the end
     * ```
     *
     * The whole 44-byte header goes through [Cipher.updateAAD], so every field
     * in it is covered by the tag. Without that, the iteration count is
     * attacker-controlled: rewrite it to 1 and the file becomes cheap to crack
     * offline. With it, any edit to the header fails decryption outright.
     */
    private const val HEADER_SIZE = 44

    private const val TAG_OFFSET = 4
    private const val VERSION_OFFSET = 7
    private const val KDF_OFFSET = 8
    private const val CIPHER_OFFSET = 9
    private const val ITERATIONS_OFFSET = 10
    private const val SALT_LENGTH_OFFSET = 14
    private const val SALT_OFFSET = 15
    private const val NONCE_LENGTH_OFFSET = 31
    private const val NONCE_OFFSET = 32

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** The HMAC each kdf id runs PBKDF2 over. */
    private fun macFor(kdf: Byte): String? = when (kdf) {
        KDF_PBKDF2_SHA512 -> "HmacSHA512"
        KDF_PBKDF2_SHA256 -> "HmacSHA256"
        else -> null
    }

    private val ENC: ByteArray = byteArrayOf('E'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte())

    /** How many bytes [looksEncrypted] needs. */
    const val MAGIC_SIZE = 4

    /** Whether a file starting with these bytes is one of ours. */
    fun looksEncrypted(head: ByteArray): Boolean =
        head.size >= MAGIC_SIZE && MAGIC.indices.all { head[it] == MAGIC[it] }

    /**
     * A fresh salt, to be stored once per install when the passphrase is set.
     *
     * One salt for all of an install's own files is fine: a salt defeats
     * precomputation across users, and the per-message requirement GCM actually
     * has is a fresh *nonce*, which [encrypt] takes from [SecureRandom] every
     * time. Keeping it stable is what lets the derived key be computed once.
     */
    fun newSalt(): ByteArray = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }

    /**
     * Writes [plaintext] to [out], encrypted.
     *
     * [out] is left open: this is called from inside `BackupSink.write`, which
     * owns the stream and closes it.
     */
    fun encrypt(
        out: OutputStream,
        passphrase: CharArray,
        salt: ByteArray,
        plaintext: String,
        iterations: Int = DEFAULT_ITERATIONS,
    ) {
        // Encoding straight into the cipher stream keeps the bundle from being
        // copied again as a ByteArray on the way past.
        OutputStreamWriter(encrypting(out, passphrase, salt, iterations), Charsets.UTF_8).use { it.write(plaintext) }
    }

    /**
     * A stream that encrypts what is written to it into [out], so a bundle need
     * not exist as one string first (#1080). Its close runs doFinal and appends
     * the tag, so it must happen exactly once; [out] stays open.
     */
    fun encrypting(
        out: OutputStream,
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): OutputStream {
        require(salt.size == SALT_SIZE) { "salt must be $SALT_SIZE bytes" }
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }

        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val header = header(iterations, salt, nonce)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(pbkdf2(passphrase, salt, iterations, KEY_SIZE, "HmacSHA512"), "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            updateAAD(header)
        }

        out.write(header)
        return CipherOutputStream(NonClosingOutputStream(out), cipher)
    }

    /** What came back out of [decrypt]. */
    sealed interface DecryptResult {

        /** The bundle text, ready for [ConfigBackup.decode]. */
        data class Ok(val text: String) : DecryptResult

        /**
         * The tag did not verify. Wrong passphrase, or the file was altered or
         * truncated — and there is no way to tell those apart, which is the
         * property a tag is supposed to have. The UI says both.
         */
        data object BadPassphraseOrCorrupt : DecryptResult

        /** Not one of ours at all. The caller should try the plaintext path. */
        data object NotEncrypted : DecryptResult

        /** Ours, but written by a newer app than this one. */
        data object UnsupportedVersion : DecryptResult
    }

    /**
     * Reads an encrypted bundle from [input]. The caller closes the stream.
     *
     * **Deliberately not streaming.** `CipherInputStream` swallows
     * `AEADBadTagException` on Android: a truncated or tampered file decrypts
     * to truncated plaintext and reports success. Downstream that plaintext
     * would reach [ConfigBackup.decode], and a bundle that parses but is
     * missing its tail is exactly the thing that gets restored over good data.
     * So the ciphertext is buffered and put through one `doFinal`, where a bad
     * tag is an exception that cannot be ignored. The buffer is the same order
     * of size as the `String` that `importConfig` already requires.
     */
    fun decrypt(input: InputStream, passphrase: CharArray): DecryptResult {
        var failure: DecryptResult = DecryptResult.BadPassphraseOrCorrupt
        val bytes = decryptBytes(input, passphrase) { failure = it } ?: return failure
        return DecryptResult.Ok(bytes.toString(Charsets.UTF_8))
    }

    /**
     * [decrypt] short of the string, for a caller that can parse from bytes and
     * would rather not hold the bundle a third time (#1080). Null after telling
     * [onFailure] why.
     */
    @Suppress("ReturnCount")
    fun decryptBytes(
        input: InputStream,
        passphrase: CharArray,
        onFailure: (DecryptResult) -> Unit = {},
    ): ByteArray? {
        val refuse = { why: DecryptResult ->
            onFailure(why)
            null
        }
        val header = ByteArray(HEADER_SIZE)
        if (!input.readFully(header)) return refuse(DecryptResult.NotEncrypted)
        if (!looksEncrypted(header)) return refuse(DecryptResult.NotEncrypted)
        if (ENC.indices.any { header[TAG_OFFSET + it] != ENC[it] }) return refuse(DecryptResult.NotEncrypted)

        if (!isSupported(header)) return refuse(DecryptResult.UnsupportedVersion)

        val iterations = header.readInt(ITERATIONS_OFFSET)
        if (iterations !in 1..MAX_ITERATIONS) return refuse(DecryptResult.UnsupportedVersion)

        val salt = header.copyOfRange(SALT_OFFSET, SALT_OFFSET + SALT_SIZE)
        val nonce = header.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + NONCE_SIZE)
        val mac = macFor(header[KDF_OFFSET]) ?: return refuse(DecryptResult.UnsupportedVersion)

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(pbkdf2(passphrase, salt, iterations, KEY_SIZE, mac), "AES"),
                    GCMParameterSpec(TAG_BITS, nonce),
                )
                updateAAD(header)
            }
            cipher.doFinal(input.readBytes())
        } catch (_: GeneralSecurityException) {
            // AEADBadTagException lives under here, and so does every other way
            // a wrong key shows up. They are all the same answer to the user.
            refuse(DecryptResult.BadPassphraseOrCorrupt)
        }
    }

    /**
     * PBKDF2 over [macAlgorithm] (`HmacSHA512` for new files), by hand.
     *
     * `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")` would be the
     * obvious way and cannot be used: that algorithm only appears in the
     * Android provider at API 26, and this app supports 24. `HmacSHA512` has
     * been there since API 1, so building the loop on top of it is one
     * implementation on every device — and one that also runs on the plain-JVM
     * test source set, where it can be checked against the platform's own
     * implementation. Public for exactly that reason: this module's tests live
     * in `:app`, so `internal` would put it out of their reach.
     */
    fun pbkdf2(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyBytes: Int,
        macAlgorithm: String = "HmacSHA256",
    ): ByteArray {
        val mac = Mac.getInstance(macAlgorithm)
        mac.init(SecretKeySpec(passphrase.utf8(), macAlgorithm))
        val blockSize = mac.macLength
        val blocks = (keyBytes + blockSize - 1) / blockSize
        val derived = ByteArray(blocks * blockSize)
        val u = ByteArray(blockSize)
        val accumulator = ByteArray(blockSize)

        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(bigEndian(block))
            // The Mac has already absorbed u by the time it writes into it, so
            // reusing the one buffer for input and output is safe and saves an
            // allocation per round of which there are a hundred thousand.
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, accumulator, 0, blockSize)
            repeat(iterations - 1) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (i in 0 until blockSize) {
                    accumulator[i] = (accumulator[i].toInt() xor u[i].toInt()).toByte()
                }
            }
            System.arraycopy(accumulator, 0, derived, (block - 1) * blockSize, blockSize)
        }
        return derived.copyOf(keyBytes)
    }

    /**
     * Whether every fixed field of [header] is one this build knows.
     *
     * Read before anything is authenticated, so a mismatch means "written by
     * something else" rather than "tampered with" — an edit to any of these
     * bytes fails the tag a moment later anyway, since the whole header is
     * associated data.
     */
    private fun isSupported(header: ByteArray): Boolean =
        header[VERSION_OFFSET] == CONTAINER_VERSION &&
            macFor(header[KDF_OFFSET]) != null &&
            header[CIPHER_OFFSET] == CIPHER_AES_GCM &&
            header[SALT_LENGTH_OFFSET].toInt() == SALT_SIZE &&
            header[NONCE_LENGTH_OFFSET].toInt() == NONCE_SIZE

    private fun header(iterations: Int, salt: ByteArray, nonce: ByteArray): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.size)
        System.arraycopy(ENC, 0, header, TAG_OFFSET, ENC.size)
        header[VERSION_OFFSET] = CONTAINER_VERSION
        header[KDF_OFFSET] = KDF_PBKDF2_SHA512
        header[CIPHER_OFFSET] = CIPHER_AES_GCM
        System.arraycopy(bigEndian(iterations), 0, header, ITERATIONS_OFFSET, Int.SIZE_BYTES)
        header[SALT_LENGTH_OFFSET] = SALT_SIZE.toByte()
        System.arraycopy(salt, 0, header, SALT_OFFSET, SALT_SIZE)
        header[NONCE_LENGTH_OFFSET] = NONCE_SIZE.toByte()
        System.arraycopy(nonce, 0, header, NONCE_OFFSET, NONCE_SIZE)
        return header
    }

    private fun bigEndian(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun ByteArray.readInt(offset: Int): Int =
        (this[offset].toInt() and 0xFF shl 24) or
            (this[offset + 1].toInt() and 0xFF shl 16) or
            (this[offset + 2].toInt() and 0xFF shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    /** Fills [into] completely, or answers false. A short read is a short file. */
    private fun InputStream.readFully(into: ByteArray): Boolean {
        var filled = 0
        while (filled < into.size) {
            val read = read(into, filled, into.size - filled)
            if (read < 0) return false
            filled += read
        }
        return true
    }

    /**
     * UTF-8 bytes without ever building a `String`, which would sit in the heap
     * until a GC felt like it and could not be cleared.
     */
    private fun CharArray.utf8(): ByteArray {
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(this))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    /**
     * Passes writes through but does not close.
     *
     * [FilterOutputStream] would turn a bulk write into one call per byte,
     * which for a multi-megabyte bundle is not a subtle difference, so the
     * array overload is overridden too.
     */
    private class NonClosingOutputStream(private val delegate: OutputStream) :
        FilterOutputStream(delegate) {

        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)

        override fun close() = flush()
    }
}

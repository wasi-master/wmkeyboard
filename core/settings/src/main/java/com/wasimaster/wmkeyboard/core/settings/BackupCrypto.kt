package com.wasimaster.wmkeyboard.core.settings

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PushbackInputStream
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
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
     * PBKDF2-HMAC-SHA256 rounds for a file small enough to go out in the
     * single-piece container (see [COMPAT_LIMIT]): OWASP's figure for
     * SHA-256, the same strength by their measure as [DEFAULT_ITERATIONS]
     * over SHA-512.
     *
     * SHA-256 because that is the only key derivation builds up to 0.6.3
     * know (they read kdf id 1 and nothing else), and those builds are still
     * what Google Play hands out while a newer one waits for review. A backup
     * made on the GitHub build has to restore on the Play one, and the other
     * way round, or moving between them costs the user their backup.
     */
    const val COMPAT_ITERATIONS = 600_000

    /**
     * The largest bundle written in the single-piece container every build
     * reads. A larger one is cut into pieces instead (#1080), which builds
     * before 0.6.5 cannot open.
     *
     * One GCM pass holds the whole bundle on both ends, and an older build
     * opens it by reading the file into memory, so this has to stay small
     * enough for that on a 256 MiB heap: a few copies of 4 MiB is nothing.
     * Nearly every backup is under it; the ones over it carry sticker packs
     * or a large learned dictionary.
     */
    const val COMPAT_LIMIT = 4 * 1024 * 1024

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

    /**
     * What files written before SHA-512 carry, and what a single-piece file
     * is written under still: see [COMPAT_ITERATIONS].
     */
    private const val KDF_PBKDF2_SHA256: Byte = 1
    private const val KDF_PBKDF2_SHA512: Byte = 2
    private const val CIPHER_AES_GCM: Byte = 1

    /**
     * One GCM pass over the whole bundle. The cipher on Android holds every
     * byte it is given until `doFinal`, in both directions, so a file like
     * this costs its whole size in heap to make and again to open (#1080).
     * Written only up to [COMPAT_LIMIT], because it is the one container
     * builds before 0.6.5 can open.
     */
    private const val CONTAINER_SINGLE: Byte = 1

    /**
     * The bundle cut into [CHUNK_SIZE] pieces, each sealed on its own, so
     * neither end ever holds more than one piece. See [encrypting].
     */
    private const val CONTAINER_CHUNKED: Byte = 2

    /**
     * Plaintext bytes per sealed piece of a version 2 file. Fixed by the
     * version rather than written in the header, so a hostile file cannot
     * ask for a piece the size of the heap.
     */
    const val CHUNK_SIZE = 64 * 1024

    private const val TAG_SIZE = TAG_BITS / 8

    /** Where a piece's index and its last-piece flag sit in its nonce. */
    private const val NONCE_INDEX_OFFSET = 7
    private const val NONCE_LAST_OFFSET = 11

    private val CHUNK_KEY_LABEL = "WMKB chunked v2".toByteArray(Charsets.US_ASCII)

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
     *   7   1  container version: 2 chunked, 1 one piece (up to COMPAT_LIMIT)
     *   8   1  kdf id: 2 PBKDF2-HMAC-SHA512 (chunked), 1 PBKDF2-HMAC-SHA256
     *   9   1  cipher id
     *  10   4  iterations, big-endian
     *  14   1  salt length
     *  15  16  salt
     *  31   1  nonce length
     *  32  12  nonce
     *  44   n  version 1: ciphertext, with the 16-byte GCM tag on the end
     *          version 2: pieces of up to CHUNK_SIZE + 16 bytes, each a
     *          ciphertext with its own tag; only the last may be shorter
     * ```
     *
     * A version 2 file is the STREAM construction (Hoang, Reyhanitabar,
     * Rogaway and Vizár) over AES-GCM. The file's own key is HMAC-SHA256 of
     * the header under the passphrase key, so the random nonce field makes
     * every file's key different and the pieces can count up from zero: a
     * piece's nonce is seven zero bytes, its index as four, and a byte that
     * is 1 on the last piece. Moving a piece changes its index, and cutting
     * the file after a whole piece leaves a last piece that was sealed as not
     * the last, so both fail a tag.
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
        compatIterations: Int = COMPAT_ITERATIONS,
    ) {
        // Encoding straight into the cipher stream keeps the bundle from being
        // copied again as a ByteArray on the way past.
        OutputStreamWriter(
            encrypting(out, passphrase, salt, iterations, compatIterations),
            Charsets.UTF_8,
        ).use { it.write(plaintext) }
    }

    /**
     * A stream that encrypts what is written to it into [out]. Its close
     * seals the file, so it must happen exactly once, and a stream never
     * closed is a file that does not open; [out] stays open.
     *
     * Up to [COMPAT_LIMIT] bytes are held and, at the close, sealed in one
     * piece under [compatIterations] rounds of PBKDF2-HMAC-SHA256: the file
     * every build back to the first encrypted backup opens. Past that, the
     * file becomes the chunked container under [iterations] rounds of
     * PBKDF2-HMAC-SHA512, written one [CHUNK_SIZE] piece at a time so neither
     * the bundle nor its ciphertext is ever held whole (#1080).
     */
    fun encrypting(
        out: OutputStream,
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
        compatIterations: Int = COMPAT_ITERATIONS,
    ): OutputStream {
        require(salt.size == SALT_SIZE) { "salt must be $SALT_SIZE bytes" }
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }
        require(compatIterations in 1..MAX_ITERATIONS) { "iterations out of range" }
        return CompatSealer(out, passphrase.copyOf(), salt.copyOf(), iterations, compatIterations)
    }

    /** The chunked container's header and sealer, opened on [out]. */
    private fun chunked(out: OutputStream, passphrase: CharArray, salt: ByteArray, iterations: Int): ChunkSealer {
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val header = header(CONTAINER_CHUNKED, KDF_PBKDF2_SHA512, iterations, salt, nonce)
        val key = chunkKey(pbkdf2(passphrase, salt, iterations, KEY_SIZE, "HmacSHA512"), header)
        out.write(header)
        return ChunkSealer(out, key, header)
    }

    /** [plain] as a single-piece file under PBKDF2-HMAC-SHA256, written to [out]. */
    private fun singlePiece(
        out: OutputStream,
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        plain: ByteArray,
        length: Int,
    ) {
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val header = header(CONTAINER_SINGLE, KDF_PBKDF2_SHA256, iterations, salt, nonce)
        val key = pbkdf2(passphrase, salt, iterations, KEY_SIZE, "HmacSHA256")
        val sealed = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(header)
                doFinal(plain, 0, length)
            }
        } catch (failure: GeneralSecurityException) {
            throw IOException(failure)
        } finally {
            key.fill(0)
        }
        out.write(header)
        out.write(sealed)
        out.flush()
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
     * A piece of a version 2 file failed its tag after the first one opened:
     * the file was cut short, edited, or had pieces moved. Thrown from the
     * stream [decrypting] returns, as an [IOException] so a parser reading it
     * fails the way it would on a short file.
     */
    class CorruptException : IOException("backup failed its integrity check")

    /**
     * Reads an encrypted bundle from [input] into one string. The caller
     * closes the stream. For a bundle that may be large, [decrypting] or
     * [decryptTo] read it without holding it whole.
     */
    fun decrypt(input: InputStream, passphrase: CharArray): DecryptResult {
        var failure: DecryptResult = DecryptResult.BadPassphraseOrCorrupt
        val bytes = decryptBytes(input, passphrase) { failure = it } ?: return failure
        return DecryptResult.Ok(bytes.toString(Charsets.UTF_8))
    }

    /**
     * [decrypt] short of the string. Null after telling [onFailure] why.
     * Holds the whole plaintext, so it is for files known to be small.
     */
    fun decryptBytes(
        input: InputStream,
        passphrase: CharArray,
        onFailure: (DecryptResult) -> Unit = {},
    ): ByteArray? {
        val opened = decrypting(NonClosingInputStream(input), passphrase, onFailure) ?: return null
        val out = ByteArrayOutputStream()
        return try {
            opened.use { it.copyTo(out) }
            out.toByteArray()
        } catch (_: CorruptException) {
            onFailure(DecryptResult.BadPassphraseOrCorrupt)
            null
        }
    }

    /**
     * Decrypts all of [input] into [out], a piece at a time: null once every
     * piece was written and checked, else why not. What [out] got before a
     * failure is not to be trusted, so the caller throws it away. Neither
     * stream is closed.
     */
    fun decryptTo(input: InputStream, passphrase: CharArray, out: OutputStream): DecryptResult? {
        var failure: DecryptResult? = null
        val opened = decrypting(NonClosingInputStream(input), passphrase) { failure = it }
            ?: return failure ?: DecryptResult.BadPassphraseOrCorrupt
        return try {
            opened.use { it.copyTo(out) }
            null
        } catch (_: CorruptException) {
            DecryptResult.BadPassphraseOrCorrupt
        }
    }

    /**
     * The plaintext of [input] as a stream, or null after telling [onFailure]
     * why it cannot be opened. Closing the stream closes [input].
     *
     * The first piece is opened here, so a wrong passphrase is answered now
     * rather than by the first read. Every later piece is checked as it is
     * read, and a bad one throws [CorruptException]: plaintext that has come
     * out is only known good once the stream has reached its end, so a
     * caller that acts on what it reads (a restore) reads to the end first,
     * or decrypts into a file with [decryptTo] and works from that.
     *
     * Never `CipherInputStream`, which on Android swallows
     * `AEADBadTagException`: a truncated file would decrypt to truncated
     * plaintext and report success. A version 1 file is one piece, so it is
     * opened with one `doFinal` over the whole of it, as it always was.
     */
    @Suppress("ReturnCount")
    fun decrypting(
        input: InputStream,
        passphrase: CharArray,
        onFailure: (DecryptResult) -> Unit = {},
    ): InputStream? {
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
        val master = pbkdf2(passphrase, salt, iterations, KEY_SIZE, mac)

        if (header[VERSION_OFFSET] == CONTAINER_CHUNKED) {
            val opener = ChunkOpener(input, chunkKey(master, header), header)
            return try {
                opener.next()
                opener
            } catch (_: CorruptException) {
                refuse(DecryptResult.BadPassphraseOrCorrupt)
            }
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(master, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(header)
            }
            val plain = cipher.doFinal(input.readBytes())
            object : ByteArrayInputStream(plain) {
                override fun close() = input.close()
            }
        } catch (_: GeneralSecurityException) {
            // AEADBadTagException lives under here, and so does every other way
            // a wrong key shows up. They are all the same answer to the user.
            refuse(DecryptResult.BadPassphraseOrCorrupt)
        }
    }

    /**
     * A version 2 file's own key: HMAC-SHA256 of its header under the
     * passphrase key. The header holds a fresh random nonce, so no two files
     * share a key, which is what lets every file number its pieces from zero.
     */
    private fun chunkKey(master: ByteArray, header: ByteArray): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        mac.update(CHUNK_KEY_LABEL)
        return SecretKeySpec(mac.doFinal(header), "AES")
    }

    /** Piece [index]'s nonce: seven zero bytes, the index, then 1 on the last. */
    private fun chunkNonce(index: Int, last: Boolean): GCMParameterSpec {
        val nonce = ByteArray(NONCE_SIZE)
        System.arraycopy(bigEndian(index), 0, nonce, NONCE_INDEX_OFFSET, Int.SIZE_BYTES)
        nonce[NONCE_LAST_OFFSET] = if (last) 1 else 0
        return GCMParameterSpec(TAG_BITS, nonce)
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
        (header[VERSION_OFFSET] == CONTAINER_CHUNKED || header[VERSION_OFFSET] == CONTAINER_SINGLE) &&
            macFor(header[KDF_OFFSET]) != null &&
            header[CIPHER_OFFSET] == CIPHER_AES_GCM &&
            header[SALT_LENGTH_OFFSET].toInt() == SALT_SIZE &&
            header[NONCE_LENGTH_OFFSET].toInt() == NONCE_SIZE

    private fun header(container: Byte, kdf: Byte, iterations: Int, salt: ByteArray, nonce: ByteArray): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.size)
        System.arraycopy(ENC, 0, header, TAG_OFFSET, ENC.size)
        header[VERSION_OFFSET] = container
        header[KDF_OFFSET] = kdf
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

    /** Fills [into] as far as [input] goes: the count, short only at its end. */
    private fun readUpTo(input: InputStream, into: ByteArray): Int {
        var filled = 0
        while (filled < into.size) {
            val read = input.read(into, filled, into.size - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    /**
     * Holds what is written to it until it outgrows [COMPAT_LIMIT]: closed
     * before then, it seals a single-piece file older builds open; past it,
     * it starts the chunked container and hands everything to a
     * [ChunkSealer] from then on. Either way the key is derived once, when
     * the choice is made.
     */
    private class CompatSealer(
        private val out: OutputStream,
        private val passphrase: CharArray,
        private val salt: ByteArray,
        private val iterations: Int,
        private val compatIterations: Int,
    ) : OutputStream() {
        private var held = ByteArray(INITIAL_HOLD)
        private var filled = 0
        private var chunks: ChunkSealer? = null
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("closed")
            chunks?.let { return it.write(b, off, len) }
            if (filled + len > COMPAT_LIMIT) {
                val sealer = chunked(out, passphrase, salt, iterations)
                chunks = sealer
                sealer.write(held, 0, filled)
                forget()
                sealer.write(b, off, len)
                return
            }
            if (filled + len > held.size) {
                val grown = held.copyOf(minOf(COMPAT_LIMIT, maxOf(held.size * 2, filled + len)))
                held.fill(0)
                held = grown
            }
            System.arraycopy(b, off, held, filled, len)
            filled += len
        }

        override fun flush() {
            chunks?.flush() ?: out.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                val sealer = chunks
                if (sealer != null) {
                    sealer.close()
                } else {
                    singlePiece(out, passphrase, salt, compatIterations, held, filled)
                }
            } finally {
                forget()
                passphrase.fill('\u0000')
            }
        }

        private fun forget() {
            held.fill(0)
            held = ByteArray(0)
            filled = 0
        }

        private companion object {
            const val INITIAL_HOLD = 64 * 1024
        }
    }

    /**
     * Seals what is written to it into [out] a piece at a time. A full piece
     * goes out only once another byte arrives, so the close always has a
     * piece to seal as the last one, if need be an empty one.
     */
    private class ChunkSealer(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
    ) : OutputStream() {
        private val cipher = Cipher.getInstance(TRANSFORMATION)
        private val plain = ByteArray(CHUNK_SIZE)
        private val sealed = ByteArray(CHUNK_SIZE + TAG_SIZE)
        private var filled = 0
        private var index = 0
        private var closed = false

        override fun write(b: Int) {
            if (closed) throw IOException("closed")
            if (filled == CHUNK_SIZE) seal(last = false)
            plain[filled++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("closed")
            var from = off
            var left = len
            while (left > 0) {
                if (filled == CHUNK_SIZE) seal(last = false)
                val n = minOf(left, CHUNK_SIZE - filled)
                System.arraycopy(b, from, plain, filled, n)
                filled += n
                from += n
                left -= n
            }
        }

        /** Only [out]: a piece is sealed when it is full, never part of one. */
        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                seal(last = true)
                out.flush()
            } finally {
                plain.fill(0)
            }
        }

        private fun seal(last: Boolean) {
            if (index == Int.MAX_VALUE) throw IOException("backup too large to seal")
            val written = try {
                cipher.init(Cipher.ENCRYPT_MODE, key, chunkNonce(index, last))
                cipher.updateAAD(header)
                cipher.doFinal(plain, 0, filled, sealed, 0)
            } catch (failure: GeneralSecurityException) {
                throw IOException(failure)
            }
            out.write(sealed, 0, written)
            index++
            filled = 0
        }
    }

    /**
     * Opens a version 2 file a piece at a time, checking each tag before any
     * of its plaintext is handed out. The last piece is the one the file
     * ends after, found by reading one byte past a full piece.
     */
    private class ChunkOpener(
        input: InputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
    ) : InputStream() {
        private val input = PushbackInputStream(input, 1)
        private val cipher = Cipher.getInstance(TRANSFORMATION)
        private val sealed = ByteArray(CHUNK_SIZE + TAG_SIZE)

        // A tag's worth over a piece: some providers size a decrypt's output
        // as its input before taking the tag off.
        private val plain = ByteArray(CHUNK_SIZE + TAG_SIZE)
        private var pos = 0
        private var limit = 0
        private var index = 0
        private var finished = false

        /** Opens the next piece into [plain]; false after the last one. */
        fun next(): Boolean {
            if (finished) return false
            val n = readUpTo(input, sealed)
            if (n < TAG_SIZE) throw CorruptException()
            val last = n < sealed.size || atEnd()
            if (!last && index == Int.MAX_VALUE) throw CorruptException()
            limit = try {
                cipher.init(Cipher.DECRYPT_MODE, key, chunkNonce(index, last))
                cipher.updateAAD(header)
                cipher.doFinal(sealed, 0, n, plain, 0)
            } catch (_: GeneralSecurityException) {
                throw CorruptException()
            }
            pos = 0
            index++
            finished = last
            return true
        }

        private fun atEnd(): Boolean {
            val next = input.read()
            if (next < 0) return true
            input.unread(next)
            return false
        }

        override fun read(): Int {
            while (pos == limit) if (!next()) return -1
            return plain[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos == limit) if (!next()) return -1
            val n = minOf(len, limit - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = limit - pos

        override fun close() {
            plain.fill(0)
            input.close()
        }
    }

    /** Passes reads through but does not close: the caller owns the stream. */
    private class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun available(): Int = delegate.available()

        override fun close() = Unit
    }
}

package com.wasimaster.wmkeyboard.core.settings

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val passphrase = "correct horse battery staple".toCharArray()
    private val salt = ByteArray(16) { it.toByte() }
    private val bundle = """{"format":"wmkeyboard-config","version":1,"sections":{}}"""

    /** Test rounds. The shipped default is far higher and far too slow for a test. */
    private val rounds = 1_000

    private fun seal(
        text: String = bundle,
        key: CharArray = passphrase,
        iterations: Int = rounds,
    ): ByteArray = ByteArrayOutputStream()
        .also { BackupCrypto.encrypt(it, key, salt, text, iterations, iterations) }
        .toByteArray()

    private fun open(bytes: ByteArray, key: CharArray = passphrase) =
        BackupCrypto.decrypt(ByteArrayInputStream(bytes), key)

    private fun sealBytes(plain: ByteArray): ByteArray = ByteArrayOutputStream()
        .also { out -> BackupCrypto.encrypting(out, passphrase, salt, rounds, rounds).use { it.write(plain) } }
        .toByteArray()

    /** Past [BackupCrypto.COMPAT_LIMIT], so the file is the chunked container. */
    private val chunkedFloor = BackupCrypto.COMPAT_LIMIT

    /** [sealBytes] of [extra] bytes beyond [chunkedFloor], each byte from [fill]. */
    private fun sealChunked(extra: Int, fill: (Int) -> Byte = { it.toByte() }): Pair<ByteArray, ByteArray> {
        val plain = ByteArray(chunkedFloor + extra, fill)
        return plain to sealBytes(plain)
    }

    /**
     * A file as builds before #1080 wrote it: container version 1, the whole
     * bundle in one GCM pass under the passphrase key, over [kdf]'s HMAC.
     */
    private fun singlePiece(text: String, kdf: Byte): ByteArray {
        val header = seal().copyOf(HEADER_SIZE).also {
            it[VERSION_OFFSET] = 1
            it[KDF_OFFSET] = kdf
        }
        val nonce = header.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + 12)
        val mac = if (kdf == 1.toByte()) "HmacSHA256" else "HmacSHA512"
        val key = BackupCrypto.pbkdf2(passphrase, salt, rounds, 32, mac)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header)
        }
        return header + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var count = 0L
            private set

        override fun read(): Int = delegate.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it > 0) count += it }
    }

    // ---- the KDF ----

    @Test
    fun `pbkdf2 matches the platform implementation`() {
        // The hand-rolled loop exists because PBKDF2WithHmacSHA256 is missing
        // from the Android provider below API 26. The JDK running these tests
        // does have it, so it is the reference to check against.
        for (iterations in listOf(1, 2, 977)) {
            val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(passphrase, salt, iterations, 32 * 8))
                .encoded
            assertArrayEquals(
                "iterations=$iterations",
                expected,
                BackupCrypto.pbkdf2(passphrase, salt, iterations, 32),
            )
        }
    }

    @Test
    fun `pbkdf2 spans more than one hash block`() {
        // 32 bytes is one SHA-256 block exactly, so the block loop never runs
        // twice in normal use and a bug in it would go unseen.
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase, salt, 64, 100 * 8))
            .encoded
        assertArrayEquals(expected, BackupCrypto.pbkdf2(passphrase, salt, 64, 100))
    }

    @Test
    fun `a non-ascii passphrase is encoded as utf-8`() {
        val unicode = "পাসওয়ার্ড🔑".toCharArray()
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(unicode, salt, 16, 32 * 8))
            .encoded
        assertArrayEquals(expected, BackupCrypto.pbkdf2(unicode, salt, 16, 32))
    }

    @Test
    fun `pbkdf2 over sha-512 matches the platform implementation`() {
        // What new files use. One SHA-512 block is 64 bytes, so the 32-byte
        // key is a prefix of it, and 100 bytes takes the block loop twice.
        for ((iterations, bytes) in listOf(1 to 32, 977 to 32, 64 to 100)) {
            val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                .generateSecret(PBEKeySpec(passphrase, salt, iterations, bytes * 8))
                .encoded
            assertArrayEquals(
                "iterations=$iterations bytes=$bytes",
                expected,
                BackupCrypto.pbkdf2(passphrase, salt, iterations, bytes, "HmacSHA512"),
            )
        }
    }

    // ---- round trip ----

    @Test
    fun `a sealed bundle comes back`() {
        assertEquals(BackupCrypto.DecryptResult.Ok(bundle), open(seal()))
    }

    @Test
    fun `a small new file is the one every older build opens`() {
        // Google Play can lag the GitHub release by weeks, and a build up to
        // 0.6.3 reads container 1 under kdf 1 and nothing else. A backup made
        // on one has to restore on the other.
        val sealed = seal()
        assertEquals(1.toByte(), sealed[VERSION_OFFSET])
        assertEquals(1.toByte(), sealed[KDF_OFFSET])
        assertEquals(sealed.size, HEADER_SIZE + bundle.length + TAG_SIZE)
    }

    @Test
    fun `a small new file is sealed the way older builds open it`() {
        // Opened here by hand, exactly as the 0.6.3 decrypt did: PBKDF2 over
        // HMAC-SHA256 at the header's count, one GCM pass, the header as AAD.
        val sealed = seal()
        val header = sealed.copyOf(HEADER_SIZE)
        val iterations = java.nio.ByteBuffer.wrap(header, ITERATIONS_OFFSET, 4).int
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase, salt, iterations, 32 * 8))
            .encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, header.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + 12)),
            )
            updateAAD(header)
        }
        assertEquals(bundle, cipher.doFinal(sealed, HEADER_SIZE, sealed.size - HEADER_SIZE).toString(Charsets.UTF_8))
    }

    @Test
    fun `the shipped rounds are owasp's for each hash`() {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypting(out, passphrase, salt, rounds).use { it.write(1) }
        val iterations = java.nio.ByteBuffer.wrap(out.toByteArray(), ITERATIONS_OFFSET, 4).int
        assertEquals(600_000, iterations)
        assertEquals(BackupCrypto.COMPAT_ITERATIONS, iterations)
        assertEquals(210_000, BackupCrypto.DEFAULT_ITERATIONS)
    }

    @Test
    fun `a large new file says sha-512 in its header`() {
        assertEquals(2.toByte(), sealChunked(1).second[KDF_OFFSET])
    }

    @Test
    fun `a file from before sha-512 still opens`() {
        // Backups already sitting in people's folders were keyed over
        // HMAC-SHA256 under kdf id 1. They must restore after the switch.
        // They are single-piece files too, container version 1.
        assertEquals(BackupCrypto.DecryptResult.Ok(bundle), open(singlePiece(bundle, kdf = 1)))
    }

    @Test
    fun `a single-piece file from before the chunked container still opens`() {
        // Every encrypted backup written before #1080 is one GCM pass under
        // container version 1. They must restore through every way in.
        val legacy = singlePiece(bundle, kdf = 2)
        assertEquals(BackupCrypto.DecryptResult.Ok(bundle), open(legacy))
        val out = ByteArrayOutputStream()
        assertEquals(null, BackupCrypto.decryptTo(ByteArrayInputStream(legacy), passphrase, out))
        assertEquals(bundle, out.toString(Charsets.UTF_8.name()))
        val stream = requireNotNull(BackupCrypto.decrypting(ByteArrayInputStream(legacy), passphrase))
        assertEquals(bundle, stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    @Test
    fun `an edited single-piece file is still refused`() {
        val legacy = singlePiece(bundle, kdf = 2)
        legacy[legacy.size - 3] = (legacy[legacy.size - 3].toInt() xor 1).toByte()
        assertEquals(BackupCrypto.DecryptResult.BadPassphraseOrCorrupt, open(legacy))
    }

    @Test
    fun `a file past the limit is the chunked container`() {
        assertEquals(2.toByte(), sealChunked(1).second[VERSION_OFFSET])
        assertEquals(1.toByte(), sealBytes(ByteArray(chunkedFloor))[VERSION_OFFSET])
    }

    // ---- pieces (#1080) ----

    @Test
    fun `every length around a piece boundary comes back`() {
        val chunk = BackupCrypto.CHUNK_SIZE
        val floor = chunkedFloor
        for (
            length in listOf(
                0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk + 7,
                floor - 1, floor, floor + 1, floor + chunk, floor + chunk + 1, floor + 2 * chunk + 7,
            )
        ) {
            val plain = ByteArray(length) { (it * 31).toByte() }
            val sealed = sealBytes(plain)
            val out = ByteArrayOutputStream()
            assertEquals("length=$length", null, BackupCrypto.decryptTo(ByteArrayInputStream(sealed), passphrase, out))
            assertArrayEquals("length=$length", plain, out.toByteArray())
        }
    }

    @Test
    fun `a file cut after a whole piece is refused`() {
        // The cut that a length check alone would miss: every piece left is
        // whole and verifies, but none of them was sealed as the last.
        val chunk = BackupCrypto.CHUNK_SIZE
        val sealed = sealChunked(chunk * 5 / 2).second
        val whole = (chunkedFloor + chunk * 5 / 2) / chunk
        for (pieces in listOf(1, whole - 1, whole)) {
            val cut = sealed.copyOf(HEADER_SIZE + pieces * (chunk + TAG_SIZE))
            assertEquals("pieces=$pieces", BackupCrypto.DecryptResult.BadPassphraseOrCorrupt, open(cut))
            assertEquals(
                "pieces=$pieces",
                BackupCrypto.DecryptResult.BadPassphraseOrCorrupt,
                BackupCrypto.decryptTo(ByteArrayInputStream(cut), passphrase, ByteArrayOutputStream()),
            )
        }
    }

    @Test
    fun `swapped pieces are refused`() {
        val chunk = BackupCrypto.CHUNK_SIZE
        val sealed = sealChunked(chunk * 3) { (it / chunk).toByte() }.second
        val piece = chunk + TAG_SIZE
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, HEADER_SIZE, swapped, HEADER_SIZE + piece, piece)
        System.arraycopy(sealed, HEADER_SIZE + piece, swapped, HEADER_SIZE, piece)
        assertEquals(BackupCrypto.DecryptResult.BadPassphraseOrCorrupt, open(swapped))
    }

    @Test
    fun `a bad piece after the first throws from the stream`() {
        val chunk = BackupCrypto.CHUNK_SIZE
        val sealed = sealChunked(chunk * 3).second
        sealed[HEADER_SIZE + chunk + TAG_SIZE + 5] = (sealed[HEADER_SIZE + chunk + TAG_SIZE + 5].toInt() xor 1).toByte()
        val stream = requireNotNull(BackupCrypto.decrypting(ByteArrayInputStream(sealed), passphrase))
        val failure = runCatching { stream.use { it.readBytes() } }.exceptionOrNull()
        assertTrue("got $failure", failure is BackupCrypto.CorruptException)
    }

    @Test
    fun `the wrong passphrase is refused when the stream opens, not later`() {
        var why: BackupCrypto.DecryptResult? = null
        val stream = BackupCrypto.decrypting(
            ByteArrayInputStream(sealChunked(10).second),
            "wrong".toCharArray(),
        ) { why = it }
        assertEquals(null, stream)
        assertEquals(BackupCrypto.DecryptResult.BadPassphraseOrCorrupt, why)
    }

    @Test
    fun `sealing writes each piece out once it is full`() {
        // What keeps the encrypt side from holding the bundle: past the
        // single-piece limit, once a piece is full and another byte arrives,
        // the piece is already in [out]. Below it nothing is written until the
        // close, which seals the one piece.
        val chunk = BackupCrypto.CHUNK_SIZE
        val held = chunkedFloor / chunk
        val out = ByteArrayOutputStream()
        val sealing = BackupCrypto.encrypting(out, passphrase, salt, rounds, rounds)
        sealing.write(ByteArray(chunkedFloor))
        assertEquals(0, out.size())
        sealing.write(ByteArray(1))
        assertEquals(HEADER_SIZE + held * (chunk + TAG_SIZE), out.size())
        sealing.write(ByteArray(chunk))
        assertEquals(HEADER_SIZE + (held + 1) * (chunk + TAG_SIZE), out.size())
        sealing.close()
        assertEquals(HEADER_SIZE + (held + 1) * (chunk + TAG_SIZE) + 1 + TAG_SIZE, out.size())
    }

    @Test
    fun `opening reads one piece ahead at most`() {
        // What keeps the decrypt side from holding the bundle: a byte of
        // plaintext costs the header and a piece, plus the byte that tells a
        // piece from the last one.
        val chunk = BackupCrypto.CHUNK_SIZE
        val sealed = sealChunked(chunk * 40).second
        val counted = CountingInputStream(ByteArrayInputStream(sealed))
        val stream = requireNotNull(BackupCrypto.decrypting(counted, passphrase))
        stream.read()
        assertTrue("read ${counted.count}", counted.count <= HEADER_SIZE + chunk + TAG_SIZE + 1)
        stream.read(ByteArray(chunk))
        assertTrue("read ${counted.count}", counted.count <= HEADER_SIZE + 2 * (chunk + TAG_SIZE) + 1)
    }

    @Test
    fun `a large backup round-trips through files a piece at a time`() {
        // 40 MiB: more than the 256 MiB heap of #1080 could hold several
        // copies of, written and read through files and never held whole.
        val total = 40L * 1024 * 1024
        val sealedFile = File.createTempFile("wmkb-large", ".enc")
        val openedFile = File.createTempFile("wmkb-large", ".json")
        try {
            val written = MessageDigest.getInstance("SHA-256")
            sealedFile.outputStream().buffered().use { raw ->
                BackupCrypto.encrypting(raw, passphrase, salt, rounds, rounds).use { sealing ->
                    val block = ByteArray(1 shl 20)
                    var left = total
                    var n = 0
                    while (left > 0) {
                        block.fill((n++).toByte())
                        val len = minOf(left, block.size.toLong()).toInt()
                        sealing.write(block, 0, len)
                        written.update(block, 0, len)
                        left -= len
                    }
                }
            }
            val pieces = (total + BackupCrypto.CHUNK_SIZE - 1) / BackupCrypto.CHUNK_SIZE
            assertEquals(HEADER_SIZE + total + pieces * TAG_SIZE, sealedFile.length())

            val failure = sealedFile.inputStream().buffered().use { input ->
                openedFile.outputStream().buffered().use { BackupCrypto.decryptTo(input, passphrase, it) }
            }
            assertEquals(null, failure)
            assertEquals(total, openedFile.length())
            val read = MessageDigest.getInstance("SHA-256")
            openedFile.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    read.update(buffer, 0, n)
                }
            }
            assertArrayEquals(written.digest(), read.digest())
        } finally {
            sealedFile.delete()
            openedFile.delete()
        }
    }

    @Test
    fun `an unknown kdf is reported as newer rather than as corrupt`() {
        val sealed = seal()
        sealed[KDF_OFFSET] = 9
        assertEquals(BackupCrypto.DecryptResult.UnsupportedVersion, open(sealed))
    }

    @Test
    fun `a large bundle comes back`() {
        // The encode path streams through the cipher rather than making one
        // more full-size copy, so it has to survive crossing a buffer boundary.
        val big = bundle.repeat(20_000)
        assertEquals(BackupCrypto.DecryptResult.Ok(big), open(seal(text = big)))
    }

    @Test
    fun `the file announces itself in its first four bytes`() {
        val sealed = seal()
        assertTrue(BackupCrypto.looksEncrypted(sealed))
        assertTrue(BackupCrypto.looksEncrypted(sealed.copyOf(BackupCrypto.MAGIC_SIZE)))
        assertFalse(BackupCrypto.looksEncrypted(bundle.toByteArray()))
        assertFalse(BackupCrypto.looksEncrypted(byteArrayOf(1, 2)))
    }

    @Test
    fun `every file gets its own nonce`() {
        // Two generations under one passphrase share a derived key, so a
        // repeated GCM nonce would break both of them at once.
        val first = seal()
        val second = seal()
        assertNotEquals(first.toList(), second.toList())
    }

    // ---- rejection ----

    @Test
    fun `the wrong passphrase is refused`() {
        assertEquals(
            BackupCrypto.DecryptResult.BadPassphraseOrCorrupt,
            open(seal(), key = "correct horse battery stapla".toCharArray()),
        )
    }

    @Test
    fun `a flipped ciphertext byte is refused`() {
        val sealed = seal()
        sealed[sealed.size - 8] = (sealed[sealed.size - 8].toInt() xor 1).toByte()
        assertEquals(BackupCrypto.DecryptResult.BadPassphraseOrCorrupt, open(sealed))
    }

    @Test
    fun `a truncated file is refused`() {
        // The regression this format exists to avoid: CipherInputStream drops
        // AEADBadTagException on Android, so a half-written backup would come
        // back as half a bundle and report success. Truncation must be a
        // failure, at every length.
        val sealed = seal()
        for (cut in listOf(1, 8, HEADER_SIZE, HEADER_SIZE + 1, sealed.size - 1)) {
            val result = open(sealed.copyOf(cut))
            assertTrue(
                "cut=$cut gave $result",
                result == BackupCrypto.DecryptResult.BadPassphraseOrCorrupt ||
                    result == BackupCrypto.DecryptResult.NotEncrypted,
            )
        }
    }

    @Test
    fun `an edited header is refused`() {
        // The iteration count has to be read before anything is authenticated,
        // so it is covered as associated data instead. Rewriting it to 1 would
        // otherwise turn the file into a cheap offline cracking target.
        val sealed = seal()
        sealed[ITERATIONS_OFFSET] = 0
        sealed[ITERATIONS_OFFSET + 1] = 0
        sealed[ITERATIONS_OFFSET + 2] = 0
        sealed[ITERATIONS_OFFSET + 3] = 1
        assertEquals(BackupCrypto.DecryptResult.BadPassphraseOrCorrupt, open(sealed))
    }

    @Test
    fun `an edited salt is refused`() {
        val sealed = seal()
        sealed[SALT_OFFSET] = (sealed[SALT_OFFSET].toInt() xor 0xFF).toByte()
        assertEquals(BackupCrypto.DecryptResult.BadPassphraseOrCorrupt, open(sealed))
    }

    @Test
    fun `a plain bundle is not mistaken for an encrypted one`() {
        assertEquals(
            BackupCrypto.DecryptResult.NotEncrypted,
            open(bundle.toByteArray()),
        )
        assertEquals(BackupCrypto.DecryptResult.NotEncrypted, open(ByteArray(0)))
    }

    @Test
    fun `a newer container is reported as such rather than as corrupt`() {
        val sealed = seal()
        sealed[VERSION_OFFSET] = 9
        assertEquals(BackupCrypto.DecryptResult.UnsupportedVersion, open(sealed))
    }

    @Test
    fun `an absurd iteration count is refused before it is run`() {
        // Otherwise a hostile file names two billion rounds and the restore
        // screen hangs for an hour.
        val sealed = seal()
        sealed[ITERATIONS_OFFSET] = 0x7F
        assertEquals(BackupCrypto.DecryptResult.UnsupportedVersion, open(sealed))
    }

    private companion object {
        const val TAG_SIZE = 16
        const val HEADER_SIZE = 44
        const val VERSION_OFFSET = 7
        const val KDF_OFFSET = 8
        const val ITERATIONS_OFFSET = 10
        const val SALT_OFFSET = 15
        const val NONCE_OFFSET = 32
    }
}

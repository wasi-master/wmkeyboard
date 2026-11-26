package com.wasimaster.wmkeyboard.ime.remote

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManager
import javax.net.ssl.X509KeyManager

/**
 * The phone's client certificate for Android TV's remote service. The TV
 * remembers the certificate it paired with, and its pairing code is a hash of
 * both sides' RSA keys, so one key is made once and kept in the app's
 * no-backup files: a restored phone pairs again rather than carrying a key a
 * TV trusts onto another device.
 *
 * The certificate is a plain self-signed X.509 v3 one, written here in DER,
 * so no Bouncy Castle and no keystore quirks stand between it and the TLS
 * handshake.
 */
object AtvIdentity {

    class Identity(val key: PrivateKey, val certificate: X509Certificate) {
        val keyManagers: Array<KeyManager> get() = arrayOf(FixedKeyManager(key, certificate))
    }

    @Volatile
    private var loaded: Identity? = null

    @Synchronized
    fun get(context: Context): Identity {
        loaded?.let { return it }
        val dir = File(context.applicationContext.noBackupFilesDir, "remote").apply { mkdirs() }
        val keyFile = File(dir, "atv-key.der")
        val certFile = File(dir, "atv-cert.der")
        val existing = runCatching {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = parseCertificate(certFile.readBytes())
            Identity(key, cert)
        }.getOrNull()
        val identity = existing ?: create().also { made ->
            keyFile.writeBytes(made.key.encoded)
            certFile.writeBytes(made.certificate.encoded)
        }
        loaded = identity
        return identity
    }

    /** Forgets the key: every TV paired with it has to be paired again. */
    @Synchronized
    fun reset(context: Context) {
        loaded = null
        val dir = File(context.applicationContext.noBackupFilesDir, "remote")
        File(dir, "atv-key.der").delete()
        File(dir, "atv-cert.der").delete()
    }

    internal fun create(now: Long = System.currentTimeMillis()): Identity {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS, SecureRandom()) }.generateKeyPair()
        val der = selfSigned(pair, now)
        return Identity(pair.private, parseCertificate(der))
    }

    private fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    /** TBSCertificate { v3, serial, sha256WithRSA, CN=WM Keyboard, validity, CN=WM Keyboard, the public key }, signed. */
    internal fun selfSigned(pair: KeyPair, now: Long): ByteArray {
        val algorithm = seq(OID_SHA256_RSA, NULL)
        val name = seq(set(seq(OID_COMMON_NAME, tlv(0x0C, COMMON_NAME.toByteArray(Charsets.UTF_8)))))
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))),
            tlv(0x02, serial.toByteArray()),
            algorithm,
            name,
            seq(utcTime(now - DAY_MS), utcTime(now + VALID_YEARS * 365L * DAY_MS)),
            name,
            pair.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private)
            update(tbs)
        }.sign()
        return seq(tbs, algorithm, tlv(0x03, byteArrayOf(0) + signature))
    }

    private fun utcTime(millis: Long): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, format.format(Date(millis)).toByteArray(Charsets.US_ASCII))
    }

    private fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(parts))

    private fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(parts))

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = value.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> {
                out.write(0x81)
                out.write(n)
            }
            n < 0x10000 -> {
                out.write(0x82)
                out.write(n shr 8)
                out.write(n and 0xFF)
            }
            else -> {
                out.write(0x83)
                out.write(n shr 16)
                out.write((n shr 8) and 0xFF)
                out.write(n and 0xFF)
            }
        }
        out.write(value)
        return out.toByteArray()
    }

    /** Presents the one certificate, whatever the TV asks for. */
    private class FixedKeyManager(private val key: PrivateKey, private val cert: X509Certificate) : X509KeyManager {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)

        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)

        override fun getPrivateKey(alias: String?): PrivateKey = key

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    private const val ALIAS = "wmkb-remote"
    private const val COMMON_NAME = "WM Keyboard"
    private const val KEY_BITS = 2048
    private const val VALID_YEARS = 20
    private const val DAY_MS = 86_400_000L
    private val OID_SHA256_RSA = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
    private val OID_COMMON_NAME = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
    private val NULL = byteArrayOf(0x05, 0x00)
}

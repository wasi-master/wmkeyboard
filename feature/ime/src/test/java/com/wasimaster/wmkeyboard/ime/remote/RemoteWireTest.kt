package com.wasimaster.wmkeyboard.ime.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey

/** The network remotes' wire formats: protobuf frames, the client certificate, LG's registration, Roku and SSDP text. */
class RemoteWireTest {

    @Test fun `protobuf messages round trip`() {
        val bytes = ProtoWriter()
            .varint(1, 2)
            .varint(2, 200)
            .message(10) { string(1, "atvremote"); string(2, "WM Keyboard") }
            .bytes(40, byteArrayOf(1, 2, 3))
            .toByteArray()
        val m = parseProto(bytes)
        assertEquals(2L, m.long(1))
        assertEquals(200L, m.long(2))
        assertEquals("atvremote", m.message(10)?.string(1))
        assertEquals("WM Keyboard", m.message(10)?.string(2))
        assertArrayEquals(byteArrayOf(1, 2, 3), m.bytes(40))
        assertFalse(m.has(11))
    }

    @Test fun `varints past one byte`() {
        val m = parseProto(ProtoWriter().varint(1, 300).varint(2, 99).toByteArray())
        assertEquals(300L, m.long(1))
        assertEquals(99L, m.long(2))
    }

    @Test fun `frames are length prefixed`() {
        val out = ByteArrayOutputStream()
        val first = ProtoWriter().varint(1, 7).toByteArray()
        val second = ByteArray(200) { it.toByte() }
        writeFrame(out, first)
        writeFrame(out, second)
        val input = ByteArrayInputStream(out.toByteArray())
        assertArrayEquals(first, readFrame(input))
        assertArrayEquals(second, readFrame(input))
        assertNull(readFrame(input))
    }

    @Test fun `the client certificate is a valid self-signed RSA certificate`() {
        val identity = AtvIdentity.create(now = 1_790_000_000_000L)
        val cert = identity.certificate
        cert.verify(cert.publicKey)
        cert.checkValidity(java.util.Date(1_790_000_000_000L))
        assertTrue(cert.subjectX500Principal.name.contains("WM Keyboard"))
        assertEquals(3, cert.version)
        assertTrue(cert.publicKey is RSAPublicKey)
    }

    @Test fun `key numbers go into the pairing hash without a sign byte`() {
        assertArrayEquals(byteArrayOf(1, 0, 1), AndroidTvPairing.unsigned(BigInteger.valueOf(65537)))
        assertArrayEquals(byteArrayOf(0x80.toByte()), AndroidTvPairing.unsigned(BigInteger.valueOf(128)))
    }

    @Test fun `the pairing proof's first byte is what the code starts with`() {
        val client = AtvIdentity.create().certificate.publicKey as RSAPublicKey
        val server = AtvIdentity.create().certificate.publicKey as RSAPublicKey
        val proof = AndroidTvPairing.secret(client, server, "00ABCD")
        // A code whose first two characters are the proof's first byte is the one the TV would show.
        val code = "%02X".format(proof[0].toInt() and 0xFF) + "ABCD"
        assertEquals(proof[0], AndroidTvPairing.secret(client, server, code)[0])
        assertEquals(32, proof.size)
    }

    @Test fun `LG's unsigned registration folds the signed permissions in`() {
        val unsigned = parseObject(LgRemote.registrationMessage(signed = false, clientKey = ""))!!
        val manifest = unsigned.child("payload")!!.child("manifest")!!
        assertNull(manifest["signatures"])
        assertNull(manifest["signed"])
        val permissions = (manifest["permissions"] as JsonArray).map { (it as JsonPrimitive).content }
        assertTrue("TEST_SECURE" in permissions)
        assertEquals(1, permissions.count { it == "CONTROL_INPUT_TEXT" })
        assertNull(unsigned.child("payload")!!["client-key"])

        val signed = parseObject(LgRemote.registrationMessage(signed = true, clientKey = "abc"))!!
        assertEquals("abc", signed.child("payload")!!.str("client-key"))
        assertTrue(signed.child("payload")!!.child("manifest")!!["signatures"] is JsonArray)
        assertEquals("register", signed.str("type"))
    }

    @Test fun `Roku's device info and SSDP headers parse`() {
        assertEquals("Living & Room", RokuRemote.tag("<x><user-device-name>Living &amp; Room</user-device-name></x>", "user-device-name"))
        assertNull(RokuRemote.tag("<user-device-name></user-device-name>", "user-device-name"))
        val headers = RemoteLan.headers("HTTP/1.1 200 OK\r\nST: roku:ecp\r\nLOCATION: http://192.168.1.5:8060/\r\n\r\n")
        assertEquals("roku:ecp", headers["st"])
        assertEquals("http://192.168.1.5:8060/", headers["location"])
    }

    @Test fun `a MAC reads with any separator`() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55),
            RemoteLan.macBytes("00:11:22:33:44:55"),
        )
        assertArrayEquals(RemoteLan.macBytes("00-11-22-33-44-55"), RemoteLan.macBytes("001122334455"))
        assertNull(RemoteLan.macBytes("00:11:22"))
    }

    @Test fun `every pad key has a code on every network kind`() {
        for (key in RemoteKey.entries) {
            assertTrue(SamsungRemote.keyName(key).startsWith("KEY_"))
            assertTrue(LgRemote.buttonName(key).isNotEmpty())
            assertTrue(AndroidTvRemote.keyCode(key) > 0)
        }
        assertNull(RokuRemote.keyName(RemoteKey.INPUT))
    }
}

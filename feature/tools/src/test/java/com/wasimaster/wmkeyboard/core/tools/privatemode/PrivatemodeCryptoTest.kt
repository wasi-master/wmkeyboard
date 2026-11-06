package com.wasimaster.wmkeyboard.core.tools.privatemode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64

/**
 * The Privatemode port (#1145) against vectors from the Go it was ported
 * from: Go 1.26's `crypto/hpke` MLKEM768-X25519 and Privatemode's own
 * `oae` package, run once to produce the hex below. A byte that differs
 * from Go here is a request their service cannot open.
 */
class PrivatemodeCryptoTest {

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val b64 = Base64.getDecoder()

    @Test
    fun `a seed expands to the key pair Go makes from it`() {
        val pair = HybridKeyPair.fromSeed(hex(KEM_SEED))
        assertEquals(1216, pair.publicKey.size)
        assertEquals(PUBLIC_KEY_SHA256, PrivatemodeCrypto.sha256(pair.publicKey).hex())
        assertEquals(SECRET_ID, PrivatemodeCrypto.secretId(pair.publicKey))
    }

    @Test
    fun `decapsulating Go's encapsulation exports Go's secret`() {
        val pair = HybridKeyPair.fromSeed(hex(KEM_SEED))
        val secret = PrivatemodeCrypto.exportSecret(pair.decapsulate(hex(KEM_ENC)))
        assertEquals(EXPORTED_SECRET, secret.hex())
    }

    @Test
    fun `a malformed encapsulation is refused`() {
        val pair = HybridKeyPair.fromSeed(hex(KEM_SEED))
        assertThrows(IOException::class.java) { pair.decapsulate(ByteArray(10)) }
    }

    @Test
    fun `the secret answer is accepted only under its own mesh CA`() {
        val pair = HybridKeyPair.fromSeed(hex(KEM_SEED))
        val ca = PrivatemodeCrypto.parseCertificate(hex(MESH_CA_DER))
        val other = PrivatemodeCrypto.parseCertificate(hex(OTHER_CA_DER))
        val enc = b64.decode(ENCAPSULATED_KEY)
        val cert = b64.decode(MESH_CERT)
        val signature = b64.decode(SIGNATURE)
        assertTrue(PrivatemodeCrypto.verifySecretResponse(ca, cert, pair.publicKey, enc, signature))
        assertFalse(PrivatemodeCrypto.verifySecretResponse(other, cert, pair.publicKey, enc, signature))
        // A signature over another key does not carry over.
        val tampered = enc.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(PrivatemodeCrypto.verifySecretResponse(ca, cert, pair.publicKey, tampered, signature))
    }

    @Test
    fun `a request Go sealed opens with the request key`() {
        val body = EncryptedBody.Decrypting(
            ByteArrayInputStream(hex(OAE_REQUEST_BODY)),
            Oae.requestOpener(hex(OAE_SECRET), hex(OAE_REQUEST_SALT)),
        )
        assertEquals("application/json", body.contentType)
        assertEquals("{\"model\":\"m\"}", body.readBytes().decodeToString())
    }

    @Test
    fun `a streamed answer Go sealed opens line by line`() {
        val opener = Oae.Requester(hex(OAE_SECRET), hex(OAE_REQUEST_SALT)).accept(hex(OAE_RESPONSE_SALT))
        val body = EncryptedBody.Decrypting(ByteArrayInputStream(hex(OAE_RESPONSE_BODY)), opener)
        assertEquals("text/event-stream", body.contentType)
        val lines = body.bufferedReader().readLines().filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}",
                "data: [DONE]",
            ),
            lines,
        )
    }

    @Test
    fun `a cut off answer fails instead of passing for a whole one`() {
        val whole = hex(OAE_RESPONSE_BODY)
        // Drop the final (empty) frame: 4 bytes of length and 29 of sealed message.
        val cut = whole.copyOf(whole.size - 33)
        val opener = Oae.Requester(hex(OAE_SECRET), hex(OAE_REQUEST_SALT)).accept(hex(OAE_RESPONSE_SALT))
        val error = assertThrows(IOException::class.java) {
            EncryptedBody.Decrypting(ByteArrayInputStream(cut), opener).readBytes()
        }
        assertEquals("Encrypted response was cut off", error.message)
    }

    @Test
    fun `a flipped byte fails authentication`() {
        val bad = hex(OAE_RESPONSE_BODY).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val opener = Oae.Requester(hex(OAE_SECRET), hex(OAE_REQUEST_SALT)).accept(hex(OAE_RESPONSE_SALT))
        assertThrows(IOException::class.java) {
            EncryptedBody.Decrypting(ByteArrayInputStream(bad), opener).readBytes()
        }
    }

    @Test
    fun `a request we seal opens with the authenticated headers in its app header`() {
        val secret = hex(OAE_SECRET)
        val requester = Oae.Requester.start(secret)
        val sealed = EncryptedBody.encryptRequest(requester.sealer, "{\"a\":1}".toByteArray(), "application/json", mapOf("X" to "y"))
        val opener = Oae.requestOpener(secret, requester.salt)
        // The first frame is the app header: decode it by hand to see the metadata.
        val first = sealed.copyOfRange(4, 4 + ((sealed[0].toInt() and 0xFF shl 24) or (sealed[1].toInt() and 0xFF shl 16) or
            (sealed[2].toInt() and 0xFF shl 8) or (sealed[3].toInt() and 0xFF)))
        val header = Json.parseToJsonElement(opener.open(first).decodeToString()).jsonObject
        assertEquals("application/json", header["content_type"]!!.jsonPrimitive.content)
        assertEquals("y", header["headers"]!!.jsonObject["X"]!!.jsonPrimitive.content)
        val again = EncryptedBody.Decrypting(ByteArrayInputStream(sealed), Oae.requestOpener(secret, requester.salt))
        assertEquals("{\"a\":1}", again.readBytes().decodeToString())
    }

    @Test
    fun `a response salt is accepted once`() {
        val requester = Oae.Requester(hex(OAE_SECRET), hex(OAE_REQUEST_SALT))
        requester.accept(hex(OAE_RESPONSE_SALT))
        assertThrows(IOException::class.java) { requester.accept(hex(OAE_RESPONSE_SALT)) }
    }

    @Test
    fun `OAE headers travel as RFC 9651 byte sequences`() {
        val raw = hex(OAE_REQUEST_SALT)
        val encoded = EncryptedBody.encodeHeader(raw)
        assertTrue(encoded.startsWith(":") && encoded.endsWith(":"))
        assertArrayEquals(raw, EncryptedBody.decodeHeader(encoded))
        assertThrows(IOException::class.java) { EncryptedBody.decodeHeader("abc") }
    }

    @Test
    fun `the token estimate counts tools and messages as they appear in the body`() {
        val body = """{"model":"m","stream":true,"messages":[{"role":"user","content":"hello there"}]}"""
        val (prepared, tokens) = PrivatemodeClient.prepare(body)
        val root = Json.parseToJsonElement(prepared) as JsonObject
        assertTrue(root["cache_salt"]!!.jsonPrimitive.content.length >= 32)
        val messages = root["messages"]!!.jsonArray.toString()
        // The raw text the service measures is a substring of what is sent.
        assertTrue(messages in prepared)
        assertEquals(messages.toByteArray().size / 4L, tokens)
    }

    @Test
    fun `report data follows Contrast's construction`() {
        val nonce = ByteArray(32) { it.toByte() }
        val manifest = "{}".toByteArray()
        val data = PrivatemodeClient.reportData(nonce, listOf(manifest), "root".toByteArray(), "mesh".toByteArray())
        assertEquals(64, data.size)
        val transition = PrivatemodeCrypto.sha256(PrivatemodeCrypto.sha256(manifest) + ByteArray(32))
        val expected = PrivatemodeCrypto.sha256(
            nonce + transition + PrivatemodeCrypto.sha256("root".toByteArray()) + PrivatemodeCrypto.sha256("mesh".toByteArray()),
        )
        assertArrayEquals(expected + ByteArray(32), data)
    }

    @Test
    fun `an attestation answer is read from Go's base64 fields`() {
        val enc = Base64.getEncoder()
        val doc = """{"version":"v1","manifests":["${enc.encodeToString("m1".toByteArray())}"],""" +
            """"root_ca":"${enc.encodeToString("r".toByteArray())}","mesh_ca":"${enc.encodeToString("c".toByteArray())}",""" +
            """"raw_attestation_doc":"${enc.encodeToString(byteArrayOf(1, 2, 3))}","attestation_type":[1,3,9901,2,1]}"""
        val parsed = PrivatemodeClient.parseAttestation(doc.toByteArray())
        assertEquals("m1", parsed.manifests.single().decodeToString())
        assertEquals("c", parsed.meshCa.decodeToString())
        assertArrayEquals(byteArrayOf(1, 2, 3), parsed.rawReport)
    }

    private companion object {
        const val KEM_SEED = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        const val PUBLIC_KEY_SHA256 = "c9a3565ffde4f72b51661be391ee13e46378d7f06dd5c8bf5af9d2cfb5b8336b"
        const val SECRET_ID = "yaNWX/3k9ytRZhvjke4T5GN41/Bt1ci/WvnSz7W4M2s="
        const val EXPORTED_SECRET = "6bcaaf11a68bdf0ce8e868fae12eef47f6eb2143736662a984acb3057ff5457d"
        const val KEM_ENC =
            "1103f57af964a69ef097f5307fe5a5ae99e6d36f4687342d9340c076b61f4a1e2ab503a5fd450c3c49077b28b319f21c93dc" +
            "35bb54b0e5ca1238bbac2ba91d3a973b10cf916a0dbb94a9eea8d7385833e814e5f13627d0c16552beb70717f1c19baca337" +
            "35891137bf99f173fa07cd728b1cb8588e25a323f3aa8f4f4fa4919c007a6144e7ff0b922aaccbcde1e7ae0e20394d8b9102" +
            "e5e7e46e8727f567d036b2073f7307bfc72ff88137f77ba7ac2c0a41ed477f5c3f11ce02b4dc6a8d66b58be454f879141a31" +
            "05bff7d50def95e70f238e2adfa057fd4304cc0ffe277166cdd9736d97197c85f4054bce3ab59fde1f9eb40b331457ae7af4" +
            "3d690ee571289ece6544c37a63632f6e404772125fe8098c3824a5f01a175b3e3c0d1629b7c3d8191ab871114ebe6d2a41e7" +
            "c67a39285e5594edbafdaa94697fe4db79605fc42f64caf98c9bb74a284aa02f1efab9dda7c697c7f08f56db917258682a32" +
            "fbc06862496d80f8fd119155e94d8358bd1c48b80c7870eec6280bca199000593e44becd536f853a8c0f6fe567fb3aca7ca5" +
            "f5d1f95423b90d7b47221b061676d184d760caf6a071d49d86a61752571bcdd2415d191bebd37dbe9b67eee655b70c8de21f" +
            "216c8fdabc73cc760da3da286b7213f687999743d84c79c0afb0bff8b5bb8ca095109632178022ba6197b29c014aa82e4136" +
            "5475b23057957cacb0e0ec3e2b362174ce73dcd5d62e266000a740198fb1618302e865d744a64a85430f73779d964b63970b" +
            "812229b1086663d86be8c8b515057ef191e5323bc110b63e670130371214d98d5f5ab573b7c320f83942b8ed23113d87f475" +
            "9055623be32e0b1a84506e564a5a3812d910c1424bc581a9a32eb572fc09c76a98032675bd67a215a0ed87eaf9d0db5b1219" +
            "7fbb6ea9f281b2a0a99c3d3b80c6ffe22e18d4b5343fca9c1f2d0b9dc1fe78ce4ecc6605f5df9cc61bfd8df2dd15c3eb433a" +
            "d73053576baf39b3ea06425abed0c910b8059585f8618bdfa9036e4279ea7769404c379304b41f13c77e45a39b6d9392b570" +
            "c02801c77910007cad111b618de72da1e17006d32d45341f735f8903ebca7800c18adee870a133653fed2fadc7b6af68df46" +
            "fb13398f1bd5c1899edb0dd9d8d329afde5422497490872c6d235e1b95d8251855c98202e8b2739af3e8a0d2f9410877eea6" +
            "4862e18fa3cb12b4a7d1a0316e428cf5d2988fbe63860f8503b578a00754a346549d45f0833800178606a5cfc3459ffcee76" +
            "3a8273962b57ed23a1411d1c0e99306a152397a72684c8073a5c16ca9f6734a82d8a17fc26fd881e5ea7d82c98a9125ae070" +
            "a534aa65bc56201a20dbc4c8a2c765c672ce80ee3174a04e376fabddb0407764d6d82eb4548a3f44fbe92e0c4937f9633078" +
            "a6261f7f5914bd02cd284b9ebafca8994627c60c4b7ba6e5c29a1bdbec89d6bc1f9c2dcdeb4cc2ab8888b3cf5748e9848190" +
            "62ccd023bc59d1e4845be7e154d51eb95dab8fbe440e14eec4de74345e6d0eb27a53710609fc38dbab0bbfb265d7ab2b8eb1" +
            "06b20a9635d5322548309b1451642240a6b96040"
        const val MESH_CA_DER =
            "308201953082011aa003020102020101300a06082a8648ce3d04030330123110300e060355040313076d6573682d63613020" +
            "170d3236313130363136353033365a180f32313236313031333137353033365a30123110300e060355040313076d6573682d" +
            "63613076301006072a8648ce3d020106052b8104002203620004388f0c6d4bb93f0f9d80ece52bfc18a52f26c6198ce48333" +
            "644d7bccfc7acddda755d4ba43f287dc341da53a018aea3ea0c0c9aaa920181ff85f0032dcd35535f3b140a11e02e6b50361" +
            "147f0ddbdb32951ab9584bc4fe9b259eb367eba6388ca3423040300e0603551d0f0101ff040403020204300f0603551d1301" +
            "01ff040530030101ff301d0603551d0e04160414ef98a179d3bf823f2fafc9af8305d63c6c67804e300a06082a8648ce3d04" +
            "03030369003066023100c97274d9cf7e420acc84fcd2d53bb87ef140516f4a49bdf66dd9c14ce94b4d60735944d8a9dadc77" +
            "2e11306e69aff33b023100c812bf4d876c8af4c34981f9b693638f8075f35f2d0281530ed6fea6bce81539908c0fff8c6689" +
            "170c3d2a33df9a0630"
        const val OTHER_CA_DER =
            "308201943082011aa003020102020101300a06082a8648ce3d04030330123110300e060355040313076d6573682d63613020" +
            "170d3236313130363136353033365a180f32313236313031333137353033365a30123110300e060355040313076d6573682d" +
            "63613076301006072a8648ce3d020106052b8104002203620004becd02c0e23522c5993434c53bb92838e1b1f07c75f02198" +
            "f7d66b1f2e6b3f329b083687c9e897dc6d3dae65fae4d912daa3ed830cd6e299b33ee2b472115ded658596e0c27fe1935f28" +
            "696a1452889b45450a521aeef7e93f31599016ceae70a3423040300e0603551d0f0101ff040403020204300f0603551d1301" +
            "01ff040530030101ff301d0603551d0e0416041458cbd080538fdc71a341505c6a7d9b4872929bdf300a06082a8648ce3d04" +
            "030303680030650230139b3eeb9278d140405a6595c6f3145486d8dc05bb4141031a95185ae480e137a11608b845b327fa68" +
            "b60167f6a7a56302310093335f7bbb1e7fd5eb07e62bf7fd8fba168edaced1030bd3722f1afbd82dfce6c53d9b353bbe76c1" +
            "a14964b84a87817a"
        const val ENCAPSULATED_KEY =
            "EQP1evlkpp7wl/Uwf+Wlrpnm029GhzQtk0DAdrYfSh4qtQOl/UUMPEkHeyizGfIck9w1u1Sw5coSOLusK6kdOpc7EM+Rag27lKnu" +
            "qNc4WDPoFOXxNifQwWVSvrcHF/HBm6yjNzWJETe/mfFz+gfNcoscuFiOJaMj86qPT0+kkZwAemFE5/8Lkiqsy83h564OIDlNi5EC" +
            "5efkbocn9WfQNrIHP3MHv8cv+IE393unrCwKQe1Hf1w/Ec4CtNxqjWa1i+RU+HkUGjEFv/fVDe+V5w8jjirfoFf9QwTMD/4ncWbN" +
            "2XNtlxl8hfQFS846tZ/eH560CzMUV6569D1pDuVxKJ7OZUTDemNjL25AR3ISX+gJjDgkpfAaF1s+PA0WKbfD2BkauHERTr5tKkHn" +
            "xno5KF5VlO26/aqUaX/k23lgX8QvZMr5jJu3SihKoC8e+rndp8aXx/CPVtuRclhoKjL7wGhiSW2A+P0RkVXpTYNYvRxIuAx4cO7G" +
            "KAvKGZAAWT5Evs1Tb4U6jA9v5Wf7Osp8pfXR+VQjuQ17RyIbBhZ20YTXYMr2oHHUnYamF1JXG83SQV0ZG+vTfb6bZ+7mVbcMjeIf" +
            "IWyP2rxzzHYNo9ooa3IT9oeZl0PYTHnAr7C/+LW7jKCVEJYyF4AiumGXspwBSqguQTZUdbIwV5V8rLDg7D4rNiF0znPc1dYuJmAA" +
            "p0AZj7FhgwLoZddEpkqFQw9zd52WS2OXC4EiKbEIZmPYa+jItRUFfvGR5TI7wRC2PmcBMDcSFNmNX1q1c7fDIPg5QrjtIxE9h/R1" +
            "kFViO+MuCxqEUG5WSlo4EtkQwUJLxYGpoy61cvwJx2qYAyZ1vWeiFaDth+r50NtbEhl/u26p8oGyoKmcPTuAxv/iLhjUtTQ/ypwf" +
            "LQudwf54zk7MZgX135zGG/2N8t0Vw+tDOtcwU1drrzmz6gZCWr7QyRC4BZWF+GGL36kDbkJ56ndpQEw3kwS0HxPHfkWjm22TkrVw" +
            "wCgBx3kQAHytERthjectoeFwBtMtRTQfc1+JA+vKeADBit7ocKEzZT/tL63Htq9o30b7EzmPG9XBiZ7bDdnY0ymv3lQiSXSQhyxt" +
            "I14bldglGFXJggLosnOa8+ig0vlBCHfupkhi4Y+jyxK0p9GgMW5CjPXSmI++Y4YPhQO1eKAHVKNGVJ1F8IM4ABeGBqXPw0Wf/O52" +
            "OoJzlitX7SOhQR0cDpkwahUjl6cmhMgHOlwWyp9nNKgtihf8Jv2IHl6n2CyYqRJa4HClNKplvFYgGiDbxMiix2XGcs6A7jF0oE43" +
            "b6vdsEB3ZNbYLrRUij9E++kuDEk3+WMweKYmH39ZFL0CzShLnrr8qJlGJ8YMS3um5cKaG9vsida8H5wtzetMwquIiLPPV0jphIGQ" +
            "YszQI7xZ0eSEW+fhVNUeuV2rj75EDhTuxN50NF5tDrJ6U3EGCfw426sLv7Jl16srjrEGsgqWNdUyJUgwmxRRZCJAprlgQA=="
        const val MESH_CERT =
            "MIIBijCCARCgAwIBAgIBAjAKBggqhkjOPQQDAzASMRAwDgYDVQQDEwdtZXNoLWNhMCAXDTI2MTEwNjE2NTAzNloYDzIxMjYxMDEz" +
            "MTc1MDM2WjAZMRcwFQYDVQQDEw5zZWNyZXQtc2VydmljZTB2MBAGByqGSM49AgEGBSuBBAAiA2IABNCZXx8ZvZg/xF6ljJPXI0vs" +
            "QLKNteEkmCgagXrYG9cmppcH5F8hxkRyKNYUTA+tDVi8XwslltdRuy3f9ijzZu4ovJaISQ6RNde5Iior2bnvBNg6oFWGqFXBlwJj" +
            "0RjEH6MxMC8wDgYDVR0PAQH/BAQDAgeAMB0GA1UdJQQWMBQGCCsGAQUFBwMBBggrBgEFBQcDAjAKBggqhkjOPQQDAwNoADBlAjBw" +
            "qdApO/XAC9Pb7Ii2nGuPQEmoMj7kmZ/sF2LJf6ErJmiCvdchmzf0IxINQSAZapACMQDWkYawFHe538dvKxiCARs+ydtHcYe+Uj5k" +
            "urkVJJUVG4CmPBYY5A+jCxNx6UFbZLE="
        const val SIGNATURE =
            "MGQCMDATA4fW1nVuVSJ+CCbDWJ4/14Hzc1VcmYKl06BvpC18D+iCepojoO2wfAOE9Xdp4wIwMkCexFI2WuLVPgnjsSKaNesXYIiD" +
            "1UKcETgc95PEA3WqegkH8qRMs8EHYqUFn2mU"
        const val OAE_SECRET = "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
        const val OAE_REQUEST_SALT = "59b8512c022c85d5786f6909baaf2f673574cdb78ffea7ef80e9ebcd25815d54"
        const val OAE_REQUEST_BODY =
            "00000040004038c5a276b44bfb1ee57cfb4781655bfb425e397efcc476f7d512b36afabaf8e3be09069a1e5ce4154eb33e96" +
            "f544eabb5f02d37688da3f96f3d4241222310000002a015f0e787317da1278f4e0b602160b3d1b0d7e3452abd754b8b34110" +
            "90c3fdafdfe57b577da13f352c90"
        const val OAE_RESPONSE_SALT = "f43129bc3ad1b5fe3a57e81b8eb07780fcfb8c48c963bd26cedd4bc1d751bf04"
        const val OAE_RESPONSE_BODY =
            "0000004100812dde5b823c2972275e6e05a6dbee928677de421e6a09ea7f1245cf45d6402cfb9959d8bf61b4d3d6bd1ea6a4" +
            "d3e950f82126f3c8be42bdf198e03bff55d6540000004e007dc8eb091c680253ff224d72c1856e21a28dcaf339ffc8714887" +
            "8bcc1a44496f7ae6b01b559ba56c67ff74d0a4b8043c3f2730ae2803f188b3d886a4ce0b5ec6f43a11b3c35a9b7eeb92ed4b" +
            "500000005b004784dfa6d722ad05395b77f7e456b1a1d0c9493d49d3032a23dedac41623cd0ad0f0e65661ea83162a683d59" +
            "0c4ff84b336ef28662029a778a0bcb26d67cf6859d58c3161bc76addecdbbcea9ec4bb2433413dcb0e01db72d8ec0000001d" +
            "01a41476a04c023dadd614de77b6da3c037f9eec7614ea688007da0a42"
    }
}

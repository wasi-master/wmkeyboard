package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.settings.sync.SyncNaming
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The link to the copy in another profile (#1017), as two copies on one
 * machine: two engines with their own mailboxes, meeting on 127.0.0.1 the way
 * the two profiles of a phone do.
 */
class ProfileLinkTest {

    private val main = "aaaaaaaa"
    private val private = "bbbbbbbb"

    private fun mailbox(): File = Files.createTempDirectory("profile-link").toFile()

    private fun name(install: String) = SyncNaming.name(install, "Phone", encrypted = false)

    private fun until(what: String, check: () -> Boolean) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (!check()) delay(POLL_MS)
        }
        assertTrue(what, check())
    }

    @Test
    fun `codes are twenty unambiguous characters and survive how people type them`() {
        val code = ProfileCode.generate()
        assertEquals(ProfileCode.LENGTH, code.length)
        assertTrue(code.none { it in "IO01" })
        assertEquals(code, ProfileCode.normalize(ProfileCode.format(code).lowercase()))
        assertEquals(code, ProfileCode.normalize(" " + ProfileCode.format(code).replace("-", " ") + " "))
        assertNull(ProfileCode.normalize(code.dropLast(1)))
        assertNull(ProfileCode.normalize(code.dropLast(1) + "0"))
        assertEquals(3, ProfileCode.format(code).count { it == '-' })
    }

    @Test
    fun `the port comes from the code and stays out of the ephemeral range`() {
        repeat(200) {
            val code = ProfileCode.generate()
            assertEquals(ProfileCode.port(code), ProfileCode.port(code))
            assertTrue(ProfileCode.port(code) in 20_000 until 32_000)
        }
    }

    @Test
    fun `a handshake with the same code opens a two-way link`() {
        val key = ProfileCode.key(ProfileCode.generate())
        val (client, server) = pipePair(key, key)
        client.send(name(main), "from main".toByteArray())
        val (gotName, gotBytes) = server.receive()!!
        assertEquals(name(main), gotName)
        assertArrayEquals("from main".toByteArray(), gotBytes)
        server.send(name(private), "from private".toByteArray())
        assertArrayEquals("from private".toByteArray(), client.receive()!!.second)
        // A second frame each way: the nonce counters stay in step.
        client.send(name(main), ByteArray(70_000) { it.toByte() })
        assertEquals(70_000, server.receive()!!.second.size)
    }

    @Test
    fun `a handshake with another code fails on both sides`() {
        val failures = AtomicInteger()
        pipePair(ProfileCode.key(ProfileCode.generate()), ProfileCode.key(ProfileCode.generate()), failures)
        assertEquals(2, failures.get())
    }

    @Test
    fun `a frame that was changed on the way does not open`() {
        val up = ByteArray(32) { 7 }
        val sent = ByteArrayOutputStream()
        link(input = ByteArray(0), output = sent, seal = up, open = ByteArray(32)).send(name(main), "x".toByteArray())
        val good = sent.toByteArray()
        assertEquals("x", String(link(input = good, open = up).receive()!!.second))
        val bad = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try {
            link(input = bad, open = up).receive()
            fail("a changed frame opened")
        } catch (e: java.security.GeneralSecurityException) {
            // Expected: the tag no longer matches.
        }
    }

    @Test
    fun `two copies link and hand each other their files`() {
        val code = ProfileCode.generate()
        val boxA = mailbox()
        val boxB = mailbox()
        File(boxA, name(main)).writeText("main's settings")
        File(boxB, name(private)).writeText("private's settings")
        val passesA = AtomicInteger()
        val passesB = AtomicInteger()
        val a = ProfileLinkEngine(code, boxA, main, onArrived = { passesA.incrementAndGet() })
        val b = ProfileLinkEngine(code, boxB, private, onArrived = { passesB.incrementAndGet() })
        a.start()
        until("A listens") { portTaken(code) }
        b.start()
        try {
            until("they link") { a.linked && b.linked }
            until("A has B's file") { File(boxA, name(private)).isFile }
            until("B has A's file") { File(boxB, name(main)).isFile }
            assertEquals("private's settings", File(boxA, name(private)).readText())
            assertEquals("main's settings", File(boxB, name(main)).readText())
            until("each ran a pass") { passesA.get() >= 1 && passesB.get() >= 1 }

            // A pass on A wrote a new file: it goes across, and B's older
            // copy of A's file goes, renamed or not.
            val renamed = SyncNaming.name(main, "Tablet", encrypted = false)
            File(boxA, renamed).writeText("main, renamed")
            a.send(renamed, "main, renamed".toByteArray())
            until("B has the new file") { File(boxB, renamed).isFile }
            until("B dropped the old one") { !File(boxB, name(main)).exists() }
            assertTrue(File(boxB, name(private)).isFile)
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun `the copy left running takes the port when the other goes`() {
        val code = ProfileCode.generate()
        val boxA = mailbox()
        val boxB = mailbox()
        val a = ProfileLinkEngine(code, boxA, main, onArrived = {})
        val b = ProfileLinkEngine(code, boxB, private, onArrived = {})
        a.start()
        until("A listens") { portTaken(code) }
        b.start()
        until("they link") { a.linked && b.linked }
        // The listening copy goes, as a private space that locks.
        a.stop()
        until("B sees it go") { !b.linked }
        val c = ProfileLinkEngine(code, mailbox(), "cccccccc", onArrived = {})
        try {
            until("B listens now") { portTaken(code) }
            c.start()
            until("a new copy links to B") { b.linked && c.linked }
        } finally {
            b.stop()
            c.stop()
        }
    }

    @Test
    fun `a stranger on the port is dropped and never handed a file`() {
        val code = ProfileCode.generate()
        val box = mailbox()
        File(box, name(main)).writeText("secret settings")
        val a = ProfileLinkEngine(code, box, main, onArrived = {})
        a.start()
        try {
            until("A listens") { portTaken(code) }
            Socket(InetAddress.getLoopbackAddress(), ProfileCode.port(code)).use { socket ->
                socket.soTimeout = 7_000
                // The right header and a nonce, then a made-up proof.
                DataOutputStream(socket.getOutputStream()).apply {
                    write("WMKP".toByteArray())
                    writeByte(1)
                    write(ByteArray(32))
                    flush()
                }
                val answer = socket.getInputStream().readNBytesCompat(5 + 32 + 32)
                assertEquals(69, answer.size)
                socket.getOutputStream().write(ByteArray(32))
                socket.getOutputStream().flush()
                // Closed with nothing more: no frame, so no file.
                assertEquals(-1, socket.getInputStream().read())
            }
            assertFalse(a.linked)
        } finally {
            a.stop()
        }
    }

    @Test
    fun `a file named for this copy or outside the mailbox is refused`() {
        val code = ProfileCode.generate()
        val boxA = mailbox()
        val boxB = mailbox()
        val a = ProfileLinkEngine(code, boxA, main, onArrived = {})
        val b = ProfileLinkEngine(code, boxB, private, onArrived = {})
        a.start()
        until("A listens") { portTaken(code) }
        b.start()
        try {
            until("they link") { a.linked && b.linked }
            // B claims to be A, then tries to climb out of A's mailbox.
            b.send(name(main), "forged".toByteArray())
            Thread.sleep(SETTLE_MS)
            b.send("../" + name(private), "escaped".toByteArray())
            Thread.sleep(SETTLE_MS)
            b.send(name(private), "real".toByteArray())
            until("the real one arrives") { File(boxA, name(private)).isFile }
            assertFalse(File(boxA, name(main)).exists())
            assertFalse(File(boxA.parentFile, name(private)).exists())
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun `a probe answers only while a copy with the code is there`() {
        val code = ProfileCode.generate()
        assertFalse(ProfileLinkEngine.probe(code))
        val a = ProfileLinkEngine(code, mailbox(), main, onArrived = {})
        a.start()
        try {
            until("A listens") { portTaken(code) }
            assertTrue(ProfileLinkEngine.probe(code))
            assertNotEquals(code, ProfileCode.generate())
        } finally {
            a.stop()
        }
    }

    // ---- helpers ----

    private fun portTaken(code: String): Boolean =
        runCatching { Socket(InetAddress.getLoopbackAddress(), ProfileCode.port(code)).close() }.isSuccess

    /** Two ends of a handshake over a socket on 127.0.0.1; [failures] counts the sides that refused. */
    private fun pipePair(
        clientKey: ByteArray,
        serverKey: ByteArray,
        failures: AtomicInteger = AtomicInteger(),
    ): Pair<ProfileWire.Link, ProfileWire.Link> {
        val listening = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var server: ProfileWire.Link? = null
        val t = thread {
            val socket = listening.accept()
            try {
                server = ProfileWire.handshake(socket.getInputStream(), socket.getOutputStream(), serverKey, client = false)
            } catch (e: java.io.IOException) {
                // A refused proof, or the client gave up first and closed its end.
                failures.incrementAndGet()
                socket.close()
            }
        }
        val socket = Socket(InetAddress.getLoopbackAddress(), listening.localPort)
        val client = try {
            ProfileWire.handshake(socket.getInputStream(), socket.getOutputStream(), clientKey, client = true)
        } catch (e: ProtocolException) {
            failures.incrementAndGet()
            socket.close()
            null
        }
        t.join(TIMEOUT_MS)
        listening.close()
        val accepted = server
        if (client == null || accepted == null) return Pair(dummy, dummy)
        return client to accepted
    }

    private val dummy: ProfileWire.Link by lazy { link(input = ByteArray(0), open = ByteArray(32)) }

    /** A link with its keys given, no handshake: one end of a frame's journey. */
    private fun link(
        input: ByteArray,
        output: ByteArrayOutputStream = ByteArrayOutputStream(),
        seal: ByteArray = ByteArray(32),
        open: ByteArray,
    ) = ProfileWire.Link(DataInputStream(ByteArrayInputStream(input)), DataOutputStream(output), seal, open)

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = read(out, got, n - got)
            if (r < 0) break
            got += r
        }
        return out.copyOf(got)
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 300L
    }
}

package com.wasimaster.wmkeyboard.core.download

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [FileDownloader] against a local server that can drop connections, answer
 * out of order, ignore ranges, redirect, or serve the wrong bytes.
 */
class FileDownloaderTest {

    @get:Rule val temp = TemporaryFolder()

    private val data = Random(7).nextBytes(6 * 1024 * 1024 + 12_345)
    private val sha = sha(data)
    private lateinit var server: FakeRangeServer

    @Before fun setUp() {
        server = FakeRangeServer(data)
    }

    @After fun tearDown() {
        server.stop()
        FileDownloader.gate = DownloadGate { true }
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun part() = File(temp.root, "model.bin.part")

    @Test fun fetchesInParallelRanges() = runBlocking {
        val target = File(temp.root, "model.bin")
        val progress = Collections.synchronizedList(ArrayList<Long>())
        FileDownloader.fetchVerified(server.url, target, data.size.toLong(), sha, NetSource.DOWNLOAD_NATIVE) {
            progress += it
        }
        assertArrayEquals(data, target.readBytes())
        assertFalse(part().exists())
        assertFalse(FileDownloader.rangesOf(part()).exists())
        assertTrue("several ranges at once, saw ${server.maxConcurrent.get()}", server.maxConcurrent.get() > 1)
        assertEquals(data.size.toLong(), progress.last())
        assertEquals("every byte once", data.size.toLong(), server.bytesServed.get())
    }

    @Test fun learnsTheLengthWhenNoneIsPinned() = runBlocking {
        val total = FileDownloader.fetch(server.url, part(), null, NetSource.DOWNLOAD_NATIVE)
        assertEquals(data.size.toLong(), total)
        assertArrayEquals(data, part().readBytes())
    }

    @Test fun piecesFinishingOutOfOrderStillLandInPlace() = runBlocking {
        // The first piece answers last: every other one is written before it.
        server.delayFor = { start -> if (start == 0L) 600L else 0L }
        FileDownloader.fetch(server.url, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE)
        assertArrayEquals(data, part().readBytes())
        assertTrue(server.completionOrder.first() != 0L)
    }

    @Test fun droppedConnectionsAreRetriedFromWhereTheyStopped() = runBlocking {
        // The first five answers are cut after 100 KB.
        server.cutFirst = 5
        server.cutAt = 100_000
        FileDownloader.fetch(server.url, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE)
        assertArrayEquals(data, part().readBytes())
        assertEquals("resumed, not refetched", data.size.toLong(), server.bytesServed.get())
    }

    @Test fun aStoppedDownloadContinuesEveryPiece() {
        // Each piece stalls after 200 KB, and the download is cancelled there.
        val stalled = CountDownLatch(1)
        server.stallAfter = 200_000
        server.onStall = { stalled.countDown() }
        runBlocking {
            val job = async(Dispatchers.IO) {
                FileDownloader.fetch(server.url, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE)
            }
            assertTrue(stalled.await(10, TimeUnit.SECONDS))
            Thread.sleep(300)
            job.cancel()
            try {
                withTimeout(10_000) { job.await() }
                fail("should have been cancelled")
            } catch (_: CancellationException) {
            }
        }
        val ranges = FileDownloader.rangesOf(part())
        assertTrue("the resume map is kept", ranges.isFile)
        val plan = RangePlan.read(ranges)!!
        val started = plan.pieces.count { it.next > it.start }
        assertTrue("several pieces had begun: $started", started > 1)
        val kept = FileDownloader.bytesDone(part())
        assertTrue(kept > 0)
        assertEquals(data.size.toLong(), part().length())

        server.stop()
        server = FakeRangeServer(data)
        runBlocking { FileDownloader.fetch(server.url, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE) }
        assertArrayEquals(data, part().readBytes())
        assertEquals("only what was missing", data.size - kept, server.bytesServed.get())
    }

    @Test fun aServerWithoutRangesIsReadAsOneStream() = runBlocking {
        server.ranges = false
        val target = File(temp.root, "model.bin")
        FileDownloader.fetchVerified(server.url, target, data.size.toLong(), sha, NetSource.DOWNLOAD_NATIVE)
        assertArrayEquals(data, target.readBytes())
        assertEquals(1, server.requests.get())
    }

    @Test fun aServerThatStopsHonouringRangesStartsOver() = runBlocking {
        // Half a download from a server with ranges, then one without.
        val half = data.size / 2
        part().writeBytes(data.copyOf(half))
        server.ranges = false
        FileDownloader.fetch(server.url, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE)
        assertArrayEquals(data, part().readBytes())
    }

    @Test fun aOneStreamPartFromBeforeIsResumed() = runBlocking {
        val half = data.size / 2
        part().writeBytes(data.copyOf(half))
        FileDownloader.fetch(server.url, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE)
        assertArrayEquals(data, part().readBytes())
        assertEquals((data.size - half).toLong(), server.bytesServed.get())
    }

    @Test fun aOneStreamPartIsResumedWithoutAPinnedSize() = runBlocking {
        val half = data.size / 3
        part().writeBytes(data.copyOf(half))
        val total = FileDownloader.fetch(server.url, part(), null, NetSource.DOWNLOAD_NATIVE)
        assertEquals(data.size.toLong(), total)
        assertArrayEquals(data, part().readBytes())
        assertEquals((data.size - half).toLong(), server.bytesServed.get())
    }

    @Test fun headersGoOnlyToTheAddressGiven() = runBlocking {
        FileDownloader.fetch(
            server.redirectUrl, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE,
            headers = mapOf("Authorization" to "Bearer secret"),
        )
        assertArrayEquals(data, part().readBytes())
        assertEquals(1, server.authorisedRedirects.get())
        // Redirects are followed by hand, so the token never reaches the target.
        assertEquals(0, server.authorisedFiles.get())
    }

    @Test fun anExpiredRedirectIsAskedForAgain() = runBlocking {
        // The signed address from /go stops working a while after the next one is handed out.
        server.signed = true
        server.rotateAfter = 2
        FileDownloader.fetch(server.redirectUrl, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE)
        assertArrayEquals(data, part().readBytes())
        assertTrue(server.redirects.get() > 1)
    }

    @Test fun aWrongHashDeletesTheBytes() = runBlocking {
        val target = File(temp.root, "model.bin")
        try {
            FileDownloader.fetchVerified(server.url, target, data.size.toLong(), "0".repeat(64), NetSource.DOWNLOAD_NATIVE)
            fail("a wrong hash must not install")
        } catch (_: DownloadException.Mismatch) {
        }
        assertFalse(target.exists())
        assertFalse(part().exists())
        assertFalse(FileDownloader.rangesOf(part()).exists())
    }

    @Test fun aDifferentLengthIsNotThePinnedFile() = runBlocking {
        try {
            FileDownloader.fetch(server.url, part(), data.size + 1L, NetSource.DOWNLOAD_NATIVE)
            fail("a shorter file is not the pinned one")
        } catch (e: DownloadException.WrongSize) {
            assertEquals(data.size.toLong(), e.actual)
        }
        assertFalse(part().exists())
    }

    @Test fun aLongerStreamIsStoppedAtThePinnedSize() = runBlocking {
        server.ranges = false
        try {
            FileDownloader.fetch(server.url, part(), data.size - 1L, NetSource.DOWNLOAD_NATIVE)
            fail("a longer file is not the pinned one")
        } catch (_: DownloadException.WrongSize) {
        }
        assertFalse(part().exists())
    }

    @Test fun followsARedirectOnceAndRangesTheTarget() = runBlocking {
        FileDownloader.fetch(server.redirectUrl, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE)
        assertArrayEquals(data, part().readBytes())
        assertEquals("only the first request was redirected", 1, server.redirects.get())
    }

    @Test fun httpErrorsAreNamed() = runBlocking {
        try {
            FileDownloader.fetch(server.url + "-missing", part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE)
            fail()
        } catch (e: DownloadException.Http) {
            assertEquals(404, e.status)
        }
    }

    @Test fun theGateStopsADownloadAndKeepsWhatArrived() = runBlocking {
        val asked = AtomicInteger()
        // Lets the first three requests through, then data saving says no.
        FileDownloader.gate = DownloadGate { asked.incrementAndGet() <= 3 }
        try {
            FileDownloader.fetch(server.url, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE, connections = 1)
            fail()
        } catch (_: DownloadException.Blocked) {
        }
        // The two pieces after the first finished before the gate closed.
        assertTrue(FileDownloader.bytesDone(part()) >= 2L * 1024 * 1024)
        assertTrue(FileDownloader.rangesOf(part()).isFile)

        FileDownloader.gate = DownloadGate { true }
        FileDownloader.fetch(server.url, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE)
        assertArrayEquals(data, part().readBytes())
    }

    @Test fun aCapStopsAFileWithNoPinnedSize() = runBlocking {
        try {
            FileDownloader.fetch(server.url, part(), null, NetSource.DOWNLOAD_NATIVE, maxBytes = 1_000_000)
            fail("longer than the cap")
        } catch (_: DownloadException.WrongSize) {
        }
        assertFalse(part().exists())
        server.ranges = false
        try {
            FileDownloader.fetch(server.url, part(), null, NetSource.DOWNLOAD_NATIVE, maxBytes = 1_000_000)
            fail("longer than the cap, in one stream")
        } catch (_: DownloadException.WrongSize) {
        }
        assertTrue("stopped early: ${server.bytesServed.get()}", server.bytesServed.get() < data.size)
    }

    @Test fun theGateIsAskedInsideAPiece() = runBlocking {
        val big = Random(9).nextBytes(20 * 1024 * 1024)
        server.stop()
        server = FakeRangeServer(big)
        val asked = AtomicInteger()
        // Yes to the requests and the first in-piece question, then no.
        FileDownloader.gate = DownloadGate { asked.incrementAndGet() <= 2 }
        try {
            FileDownloader.fetch(server.url, part(), big.size.toLong(), NetSource.DOWNLOAD_NATIVE, connections = 1)
            fail()
        } catch (_: DownloadException.Blocked) {
        }
        assertTrue(FileDownloader.bytesDone(part()) in 1 until big.size.toLong())
    }

    @Test fun aSmallFileIsOneRequest() = runBlocking {
        val small = data.copyOf(50_000)
        server.stop()
        server = FakeRangeServer(small)
        FileDownloader.fetch(server.url, part(), small.size.toLong(), NetSource.DOWNLOAD_NATIVE)
        assertArrayEquals(small, part().readBytes())
        assertEquals(1, server.requests.get())
    }

    @Test fun aGarbledResumeMapStartsOver() = runBlocking {
        part().writeBytes(ByteArray(data.size))
        FileDownloader.rangesOf(part()).writeText("wmdl 1 ${data.size}\n0 10 5\n")
        FileDownloader.fetch(server.url, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE)
        assertArrayEquals(data, part().readBytes())
    }

    @Test fun aMirrorIsTriedFirst() = runBlocking {
        val origin = FakeRangeServer(data)
        try {
            FileDownloader.fetchVia(origin.url, server.url, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE)
            assertArrayEquals(data, part().readBytes())
            assertEquals(0, origin.requests.get())
        } finally {
            origin.stop()
        }
    }

    @Test fun aMirrorWithoutTheFileFallsBackToTheOrigin() = runBlocking {
        val origin = FakeRangeServer(data)
        try {
            FileDownloader.fetchVia(origin.url, server.url + "-missing", part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE)
            assertArrayEquals(data, part().readBytes())
            assertEquals(data.size.toLong(), origin.bytesServed.get())
        } finally {
            origin.stop()
        }
    }

    @Test fun theOriginCarriesOnFromTheMirrorsPieces() = runBlocking {
        // Every answer from the mirror breaks off after 300 KB, so it gives up.
        server.cutFirst = Int.MAX_VALUE
        server.cutAt = 300_000
        val origin = FakeRangeServer(data)
        try {
            FileDownloader.fetchVia(origin.url, server.url, part(), data.size.toLong(), NetSource.DOWNLOAD_NATIVE)
            assertArrayEquals(data, part().readBytes())
            assertTrue(
                "the origin sent only what the mirror had not: ${origin.bytesServed.get()}",
                origin.bytesServed.get() < data.size,
            )
        } finally {
            origin.stop()
        }
    }

    @Test fun aBadCopyOnTheMirrorIsReplacedFromTheOrigin() = runBlocking {
        server.stop()
        server = FakeRangeServer(Random(8).nextBytes(data.size))
        val origin = FakeRangeServer(data)
        val target = File(temp.root, "model.bin")
        try {
            FileDownloader.fetchVerifiedVia(origin.url, server.url, target, data.size.toLong(), sha, NetSource.DOWNLOAD_NATIVE)
            assertArrayEquals(data, target.readBytes())
            assertEquals(data.size.toLong(), origin.bytesServed.get())
        } finally {
            origin.stop()
        }
    }

    @Test fun onlyTheMirroredReleasesMoveToTheMirror() {
        val origin = "https://github.com/wasi-master/wmkeyboard-data/releases/download/"
        val base = "https://mirror.example/"
        assertEquals(
            "https://mirror.example/models/abc-encoder.onnx",
            DataMirror.mirrored(origin + "models/abc-encoder.onnx", origin, base),
        )
        assertEquals(
            "https://mirror.example/natives/abc-libwmx.so",
            DataMirror.mirrored(origin + "natives/abc-libwmx.so", origin, base),
        )
        assertEquals(null, DataMirror.mirrored(origin + "language-packs/bn.zip", origin, base))
        assertEquals(null, DataMirror.mirrored(origin + "models/a/b", origin, base))
        assertEquals(null, DataMirror.mirrored("https://github.com/someone/else/releases/download/models/x", origin, base))
        assertEquals(null, DataMirror.mirrored("https://huggingface.co/x/resolve/main/models/y", origin, base))
    }

    @Test fun aGithubProxyTakesOnlyGithubAddresses() {
        val raw = "https://raw.githubusercontent.com/iDvel/rime-ice/main/rime_ice.zip"
        assertEquals("https://gh.example/$raw", DataMirror.proxied(raw, "https://gh.example/"))
        assertEquals("https://gh.example/$raw", DataMirror.proxied(raw, " https://gh.example "))
        assertEquals("https://p.example/get?u=$raw", DataMirror.proxied(raw, "https://p.example/get?u={url}"))
        val release = "https://github.com/wasi-master/wmkeyboard-data/releases/download/natives/abc-libwmrime.so"
        assertEquals("https://gh.example/$release", DataMirror.proxied(release, "https://gh.example"))
        assertEquals(null, DataMirror.proxied("https://huggingface.co/x/resolve/main/y", "https://gh.example/"))
        assertEquals(null, DataMirror.proxied("https://github.com.evil.example/x", "https://gh.example/"))
        assertEquals(null, DataMirror.proxied(raw, "gh.example"))
        assertEquals(null, DataMirror.proxied(raw, "https://gh.example/{url}/{url}"))
    }

    @Test fun planRoundTrips() {
        val plan = RangePlan.split(10_000_000, 0, 1_000_000, 3_000_000)
        plan.pieces[1].advance(1234)
        val file = File(temp.root, "x.ranges")
        plan.write(file)
        val read = RangePlan.read(file)!!
        assertEquals(10_000_000L, read.total)
        assertEquals(listOf(0L, 1_000_000L, 4_000_000L, 7_000_000L), read.pieces.map { it.start })
        assertEquals(1234L, read.done)
    }

    /**
     * A tiny HTTP/1.1 server for one file at `/file`, one request per
     * connection, with switches for the ways real servers go wrong. `/go`
     * redirects to `/file`. Raw sockets rather than the JDK's HttpServer,
     * which cannot drop a connection halfway through a body.
     */
    private class FakeRangeServer(private val body: ByteArray) {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool()
        private val inFlight = AtomicInteger()
        private val cuts = AtomicInteger()

        val url get() = "http://127.0.0.1:${socket.localPort}/file"
        val redirectUrl get() = "http://127.0.0.1:${socket.localPort}/go"

        @Volatile var ranges = true

        /** Milliseconds to wait between the headers and the body of the range starting at a given byte. */
        @Volatile var delayFor: (Long) -> Long = { 0L }

        /** The first this many responses are cut after [cutAt] bytes of body. */
        @Volatile var cutFirst = 0
        @Volatile var cutAt = 0

        /** Bytes per response after which it stops sending, without closing; -1 never. */
        @Volatile var stallAfter = -1
        @Volatile var onStall: () -> Unit = {}

        /** /go hands out `/file?t=<token>`; only the newest two tokens are served. */
        @Volatile var signed = false

        /** With [signed], the token changes after this many requests for the file. */
        @Volatile var rotateAfter = Int.MAX_VALUE
        private val token = AtomicInteger(1)
        private val sinceRotation = AtomicInteger()

        val requests = AtomicInteger()
        val redirects = AtomicInteger()
        val authorisedRedirects = AtomicInteger()
        val authorisedFiles = AtomicInteger()
        val bytesServed = AtomicLong()
        val maxConcurrent = AtomicInteger()
        val completionOrder: MutableList<Long> = Collections.synchronizedList(ArrayList())

        init {
            pool.execute {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    pool.execute { client.use { serve(it) } }
                }
            }
        }

        private fun serve(client: Socket) {
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            val target = requestLine.split(' ')[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val out = client.getOutputStream()
            fun head(status: String, vararg lines: String) {
                val text = (listOf("HTTP/1.1 $status") + lines + "Connection: close").joinToString("\r\n") + "\r\n\r\n"
                out.write(text.toByteArray(Charsets.ISO_8859_1))
                out.flush()
            }
            when (path) {
                "/go" -> {
                    redirects.incrementAndGet()
                    if ("authorization" in headers) authorisedRedirects.incrementAndGet()
                    val location = if (signed) "$url?t=${token.get()}" else url
                    head("302 Found", "Location: $location", "Content-Length: 0")
                    return
                }
                "/file" -> {
                    if ("authorization" in headers) authorisedFiles.incrementAndGet()
                    val t = query.removePrefix("t=").toIntOrNull()
                    // An address stays good until the one after it is handed out and used up.
                    if (signed && (t == null || t < token.get() - 1)) {
                        head("403 Forbidden", "Content-Length: 0")
                        return
                    }
                    if (signed && sinceRotation.incrementAndGet() >= rotateAfter) {
                        sinceRotation.set(0)
                        token.incrementAndGet()
                    }
                }
                else -> {
                    head("404 Not Found", "Content-Length: 0")
                    return
                }
            }
            requests.incrementAndGet()
            maxConcurrent.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                val range = headers["range"]?.takeIf { ranges }?.removePrefix("bytes=")?.split('-')
                val start = range?.get(0)?.toLong() ?: 0L
                val end = range?.get(1)?.takeIf { it.isNotEmpty() }?.toLong()?.coerceAtMost(body.size - 1L)
                    ?: (body.size - 1L)
                val length = end - start + 1
                if (range != null) {
                    head("206 Partial Content", "Content-Range: bytes $start-$end/${body.size}", "Content-Length: $length")
                } else {
                    head("200 OK", "Content-Length: $length")
                }
                Thread.sleep(delayFor(start))
                val cut = if (cuts.incrementAndGet() <= cutFirst) cutAt.toLong() else -1L
                var at = start
                var sent = 0L
                while (at <= end) {
                    if (cut in 0..sent) return
                    if (stallAfter in 0..sent) {
                        onStall()
                        Thread.sleep(60_000)
                        return
                    }
                    val n = minOf(16 * 1024L, end - at + 1).toInt()
                    out.write(body, at.toInt(), n)
                    bytesServed.addAndGet(n.toLong())
                    at += n
                    sent += n
                }
                out.flush()
                completionOrder += start
            } catch (_: Exception) {
            } finally {
                inFlight.decrementAndGet()
            }
        }

        fun stop() {
            socket.close()
            pool.shutdownNow()
        }
    }
}

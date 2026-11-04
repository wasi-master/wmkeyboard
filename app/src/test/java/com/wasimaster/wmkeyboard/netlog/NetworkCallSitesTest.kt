package com.wasimaster.wmkeyboard.netlog

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the network activity log's promise, "every request the keyboard's own
 * code makes", true after this commit.
 *
 * Any source file that opens a connection — an `HttpURLConnection`, an OkHttp
 * client, a socket, an image loader — must also route it through the log:
 * `NetLog`, `NetLogInterceptor`, or KDE Connect's `KdeTrafficMeter`. A new
 * client that opens its own connection and forgets fails here, which is far
 * cheaper than a user noticing a request the screen never showed them.
 *
 * [EXEMPT] is the short list of files that open connections the log records
 * elsewhere, each with the reason. Keep the reasons honest: the docs page
 * repeats them.
 */
class NetworkCallSitesTest {

    private val opensConnection = Regex(
        """openConnection\(|OkHttpClient\.Builder\(|OkHttpClient\(\)|\bSocket\(\)|\bSocket\(|""" +
            """ServerSocket\(|DatagramSocket\(|MulticastSocket\(|ImageLoader\.Builder\(|createSocket\(""",
    )

    private val recordsIt = Regex("""\bNetLog\b|NetLogInterceptor|KdeTrafficMeter|KdeTrafficTap""")

    @Test
    fun everyConnectionIsLogged() {
        val root = listOf(File(".."), File("."))
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the repository root from ${File("").absolutePath}")
        val sources = listOf("app", "core", "feature").flatMap { top ->
            File(root, top).walkTopDown()
                .onEnter { dir -> dir.name != "build" && dir.name != "test" && dir.name != "androidTest" }
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        }
        assertTrue("found no sources under $root", sources.size > 100)

        val offenders = sources.mapNotNull { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val text = file.readText()
            when {
                !opensConnection.containsMatchIn(text) -> null
                recordsIt.containsMatchIn(text) -> null
                EXEMPT.keys.any { path.endsWith(it) } -> null
                else -> path
            }
        }
        assertTrue(
            "These files open network connections without going through the network activity log " +
                "(NetLog, NetLogInterceptor or KdeTrafficMeter):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * A build without the internet permission (#292) must fail an OkHttp request
     * with an `IOException`, not the `SecurityException` the host lookup throws.
     * The lookup runs before any network interceptor, so the gate has to be an
     * application interceptor on every client.
     */
    @Test
    fun everyOkHttpClientIsGated() {
        val root = listOf(File(".."), File("."))
            .first { File(it, "settings.gradle.kts").isFile }
        val ungated = listOf("app", "core", "feature").flatMap { top ->
            File(root, top).walkTopDown()
                .onEnter { dir -> dir.name != "build" && dir.name != "test" && dir.name != "androidTest" }
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        }.filter { file ->
            val text = file.readText()
            "OkHttpClient.Builder(" in text && "addInterceptor(InternetGate)" !in text
        }.map { it.relativeTo(root).invariantSeparatorsPath }
        assertTrue(
            "These files build an OkHttp client without .addInterceptor(InternetGate):\n" +
                ungated.joinToString("\n"),
            ungated.isEmpty(),
        )
    }

    @Test
    fun exemptionsStillExist() {
        val root = listOf(File(".."), File("."))
            .first { File(it, "settings.gradle.kts").isFile }
        val all = listOf("app", "core", "feature").flatMap { top ->
            File(root, top).walkTopDown().filter { it.isFile && it.extension == "kt" }
                .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        }
        val stale = EXEMPT.keys.filter { key -> all.none { it.endsWith(key) } }
        assertTrue("Exempt files that no longer exist, drop them: $stale", stale.isEmpty())
    }

    private companion object {
        val EXEMPT = mapOf(
            // Accepts and dials the control connections; each becomes a KdeLink,
            // which reports itself through the traffic meter. Also sends the
            // small UDP identity announcements KDE Connect discovery is made of,
            // which carry no user data and are not logged.
            "core/kdeconnect/LanTransport.kt" to "links counted in Link.kt; UDP discovery",
            // Wraps sockets already opened elsewhere in TLS; opens nothing itself.
            "core/kdeconnect/Tls.kt" to "wraps existing sockets",
            // Opens the socket for SmbSink, which holds the log row and counts
            // every frame through the traffic callback it hands in.
            "sink/smb/Smb2Client.kt" to "logged by SmbSink",
            // JvmHaHttp, for tests and plain-JVM hosts; the keyboard hands the
            // Home Assistant code its metered ToolHaHttp instead.
            "homeassistant/HaHttp.kt" to "JvmHaHttp is test-only",
            // Coil over MediaStore content URIs; nothing here touches the network.
            "gallery/GalleryThumbs.kt" to "content URIs only",
            // Opens the socket for NfsSink, which holds the log row and counts
            // the traffic through the callback it hands in.
            "sink/nfs/OncRpc.kt" to "logged by NfsSink",
            // Only listen, for devices on the local network to fetch or send
            // while the user has the tool open. What LocalSend sends out goes
            // through the logged client its caller hands it.
            "send/LanShare.kt" to "listens only",
            "send/LocalSend.kt" to "listens; sends through the caller's logged client",
            // Binds and dials 127.0.0.1 only, between this copy of the keyboard
            // and the one in a private space or work profile on the same phone
            // (#1017). Nothing it sends leaves the device.
            "sink/ProfileLink.kt" to "loopback between profiles only",
        ).mapKeys { (key, _) ->
            // Written short above; matched against the full source path.
            key.replace("core/kdeconnect/", "core/kdeconnect/src/main/java/com/wasimaster/wmkeyboard/core/kdeconnect/")
        }
    }
}

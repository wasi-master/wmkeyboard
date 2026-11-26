package com.wasimaster.wmkeyboard.core.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.FtpConfig
import com.wasimaster.wmkeyboard.core.settings.S3Config
import com.wasimaster.wmkeyboard.core.settings.SftpConfig
import com.wasimaster.wmkeyboard.core.settings.SmbConfig
import com.wasimaster.wmkeyboard.core.settings.remote.RemoteAdapters
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Files tool's servers and clouds against real servers: every way a file
 * is made, moved, replaced, renamed and removed through [RemoteFiles] and
 * [DocFiles], and the promises that keep files safe: no half file under a
 * real name, no name written over, a move that deletes only after the copy.
 *
 * Opt-in like LiveSinkRoundTripTest: `WMKB_LIVE_SINKS=1` with rclone on the
 * PATH stands up WebDAV, SFTP, FTP and S3 on localhost; `WMKB_LIVE_SSHD`
 * (port:user:keyfile) and `WMKB_LIVE_SMB` (port:share:user:password[:noenc])
 * add OpenSSH and Samba.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteFilesLiveTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val servers = mutableListOf<Process>()
    private val root: File = Files.createTempDirectory("wmkb-remote").toFile()

    @After
    fun tearDown() {
        RemoteFiles.closeAll()
        servers.forEach { it.destroy() }
        root.deleteRecursively()
    }

    private fun rclone(): String? {
        if (System.getenv("WMKB_LIVE_SINKS") != "1") return null
        return listOf("/opt/homebrew/bin/rclone", "/usr/local/bin/rclone", "/usr/bin/rclone").firstOrNull { File(it).canExecute() }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun start(vararg command: String, port: Int) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(File(root, "server-$port.log")).start()
        servers += process
        repeat(50) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return
            Thread.sleep(100)
        }
        error("server did not start on $port: " + File(root, "server-$port.log").readText())
    }

    /** Points the store at [location] alone. */
    private fun use(location: BackupLocation): String {
        RemoteFiles.source = object : RemotePlaces {
            override fun places(context: Context) = listOf(RemotePlace(location.id, "test", location.type.id))
            override fun open(context: Context, id: String): RemoteFs? = RemoteAdapters.open(context, location)
        }
        return RemoteFiles.pathOf(context, location.id, emptyList())
    }

    private fun names(dir: String): Set<String> = DocFiles.list(context, dir, showHidden = true)!!.map { it.name }.toSet()

    private fun bytes(path: String): ByteArray = DocFiles.input(context, path)!!.use { it.readBytes() }

    private fun child(dir: String, name: String): String =
        DocFiles.list(context, dir, showHidden = true)!!.first { it.name == name }.path

    /** The raw listing, our hidden temporary names included. */
    private fun raw(location: BackupLocation, dir: List<String>): List<String> =
        RemoteAdapters.open(context, location)!!.use { fs -> fs.list(dir).map { it.name } }

    private fun scenario(location: BackupLocation) = runBlocking {
        val top = use(location)
        val work = DocFiles.newFolder(context, top, "wm-test-${Random.nextInt(100_000)}")
        assertNotNull("new folder", work)
        val dir = work!!
        val folderNames = RemoteFiles.parse(dir)!!.second

        // A new file lands whole under its own name, and nothing temporary is left.
        val payload = Random.nextBytes(3 * 1024 * 1024 + 17)
        val a = RemoteFiles.write(context, dir, "a.bin", { it.write(payload) })
        assertNotNull("write", a)
        assertArrayEquals(payload, bytes(a!!))
        assertEquals(setOf("a.bin"), raw(location, folderNames).toSet())

        // The same name again is a second file, never the first one written over.
        val second = RemoteFiles.write(context, dir, "a.bin", { it.write(byteArrayOf(1, 2, 3)) })
        assertEquals(setOf("a.bin", "a (1).bin"), names(dir))
        assertArrayEquals(payload, bytes(a))
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes(second!!))

        // A source that fails partway changes nothing on the server.
        val failed = RemoteFiles.write(context, dir, "broken.bin", { out ->
            out.write(ByteArray(1000))
            throw IOException("the source broke")
        })
        assertNull(failed)
        assertEquals(setOf("a.bin", "a (1).bin"), raw(location, folderNames).toSet())

        // A stop during the upload leaves no file and no temporary one behind.
        var sent = 0L
        val stopped = runCatching {
            RemoteFiles.write(context, dir, "stopped.bin", { it.write(Random.nextBytes(2 * 1024 * 1024)) }, onSent = { sent += it }, check = {
                if (sent > 64 * 1024) throw kotlinx.coroutines.CancellationException("stop")
            })
        }
        assertTrue("the stop came through", stopped.isFailure || stopped.getOrNull() == null)
        assertEquals(setOf("a.bin", "a (1).bin"), raw(location, folderNames).toSet())

        // Rename refuses a taken name and leaves both files as they were.
        assertNull(RemoteFiles.rename(context, second, "a.bin"))
        assertArrayEquals(payload, bytes(a))
        val renamed = RemoteFiles.rename(context, second, "b.bin")
        assertNotNull(renamed)
        assertEquals(setOf("a.bin", "b.bin"), names(dir))

        // A folder, a move into it (by the server where it can), the bytes intact.
        val sub = DocFiles.newFolder(context, dir, "sub")!!
        assertNull("a taken folder name is refused", DocFiles.newFolder(context, dir, "sub"))
        val moved = DocFiles.transfer(context, listOf(a), sub, move = true)
        assertEquals(1, moved.done)
        assertEquals(setOf("b.bin", "sub"), names(dir))
        assertArrayEquals(payload, bytes(child(sub, "a.bin")))

        // From the phone and back: a folder tree up, then down again.
        val local = File(root, "local/tree").apply { mkdirs() }
        File(local, "one.txt").writeText("one")
        File(local, "deep").mkdirs()
        File(local, "deep/two.bin").writeBytes(payload)
        val up = DocFiles.transfer(context, listOf(local.path), dir, move = false)
        assertEquals(1, up.done)
        val tree = child(dir, "tree")
        assertEquals(setOf("one.txt", "deep"), names(tree))
        assertArrayEquals(payload, bytes(child(child(tree, "deep"), "two.bin")))
        val down = File(root, "down").apply { mkdirs() }
        assertEquals(1, DocFiles.transfer(context, listOf(tree), down.path, move = false).done)
        assertArrayEquals(payload, File(down, "tree/deep/two.bin").readBytes())

        // Replace: the old file goes only once the new one is whole, and one file keeps the name.
        val replacement = File(root, "b.bin").apply { writeBytes(Random.nextBytes(1234)) }
        val replaced = DocFiles.transfer(context, listOf(replacement.path), dir, move = false, clash = Clash.REPLACE)
        assertEquals(1, replaced.done)
        assertEquals(setOf("b.bin", "sub", "tree"), names(dir))
        assertArrayEquals(replacement.readBytes(), bytes(child(dir, "b.bin")))

        // The provider's copy is the file.
        assertArrayEquals(replacement.readBytes(), RemoteFiles.cached(context, child(dir, "b.bin"))!!.readBytes())

        // Straight to the adapter: it must refuse to write or rename over a name.
        RemoteAdapters.open(context, location)!!.use { fs ->
            val src = File(root, "over.bin").apply { writeBytes(byteArrayOf(9)) }
            val overwrote = runCatching { fs.upload(folderNames, "b.bin", src) {} }.isSuccess
            val renamedOver = runCatching { fs.rename(folderNames + "sub", "b.bin") }.isSuccess
            assertArrayEquals("b.bin survived", replacement.readBytes(), bytes(child(dir, "b.bin")))
            assertFalse("upload over an existing name was allowed", overwrote)
            assertFalse("rename over an existing name was allowed", renamedOver)
        }

        // The whole test folder, removed one entry at a time.
        assertTrue(RemoteFiles.remove(context, dir))
        assertFalse(names(top).contains(folderNames.last()))
    }

    @Test
    fun webdav() {
        val rclone = rclone()
        assumeTrue("set WMKB_LIVE_SINKS=1 with rclone installed", rclone != null)
        val port = freePort()
        val data = File(root, "dav").apply { mkdirs() }
        start(rclone!!, "serve", "webdav", "--addr", "127.0.0.1:$port", "--user", "wm", "--pass", "secret", data.path, port = port)
        scenario(
            BackupLocation(
                id = "dav1",
                type = BackupDestination.WEBDAV,
                webDavUrl = "http://127.0.0.1:$port/",
                webDavUser = "wm",
                webDavPassword = "secret",
            ),
        )
    }

    @Test
    fun sftp() {
        val rclone = rclone()
        assumeTrue("set WMKB_LIVE_SINKS=1 with rclone installed", rclone != null)
        val port = freePort()
        val data = File(root, "sftp").apply { mkdirs() }
        start(rclone!!, "serve", "sftp", "--addr", "127.0.0.1:$port", "--user", "wm", "--pass", "secret", data.path, port = port)
        scenario(
            BackupLocation(
                id = "sftp1",
                type = BackupDestination.SFTP,
                sftp = SftpConfig(host = "127.0.0.1", port = port, user = "wm", password = "secret"),
            ),
        )
    }

    @Test
    fun sftpOpenSsh() {
        val spec = System.getenv("WMKB_LIVE_SSHD")?.split(':') ?: return assumeTrue("WMKB_LIVE_SSHD unset", false)
        val (port, user, keyFile) = spec
        scenario(
            BackupLocation(
                id = "sshd1",
                type = BackupDestination.SFTP,
                sftp = SftpConfig(host = "127.0.0.1", port = port.toInt(), user = user, privateKey = File(keyFile).readText()),
            ),
        )
    }

    @Test
    fun ftp() {
        val rclone = rclone()
        assumeTrue("set WMKB_LIVE_SINKS=1 with rclone installed", rclone != null)
        val port = freePort()
        val data = File(root, "ftp").apply { mkdirs() }
        start(
            rclone!!, "serve", "ftp", "--addr", "127.0.0.1:$port",
            "--user", "wm", "--pass", "secret", data.path,
            port = port,
        )
        scenario(
            BackupLocation(
                id = "ftp1",
                type = BackupDestination.FTP,
                ftp = FtpConfig(host = "127.0.0.1", port = port, user = "wm", password = "secret", secure = false),
            ),
        )
    }

    @Test
    fun s3() {
        val rclone = rclone()
        assumeTrue("set WMKB_LIVE_SINKS=1 with rclone installed", rclone != null)
        val port = freePort()
        val data = File(root, "s3").apply { mkdirs() }
        File(data, "bucket").mkdirs()
        start(
            rclone!!, "serve", "s3", "--addr", "127.0.0.1:$port", "--auth-key", "AKIDTEST,SECRETTEST", data.path,
            port = port,
        )
        scenario(
            BackupLocation(
                id = "s3one",
                type = BackupDestination.S3,
                s3 = S3Config(endpoint = "http://127.0.0.1:$port", region = "us-east-1", bucket = "bucket", accessKeyId = "AKIDTEST", secretAccessKey = "SECRETTEST", pathStyle = true),
            ),
        )
    }

    @Test
    fun smb() {
        val spec = System.getenv("WMKB_LIVE_SMB")?.split(':') ?: return assumeTrue("WMKB_LIVE_SMB unset", false)
        scenario(
            BackupLocation(
                id = "smb1",
                type = BackupDestination.SMB,
                smb = SmbConfig(
                    host = "127.0.0.1",
                    port = spec[0].toInt(),
                    share = spec[1],
                    user = spec[2],
                    password = spec[3],
                    encrypt = spec.getOrNull(4) != "noenc",
                ),
            ),
        )
    }
}

package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** The parts of plugin developer mode that need no phone: who gets in, what the replies say, uploads, and the update hook. */
class PluginDevTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- the gate ------------------------------------------------------

    @Test
    fun `the adb shell gets in only while developer mode is on`() {
        assertEquals(PluginDevGate.Verdict.ALLOWED, PluginDevGate.check(2000, developerMode = true))
        assertEquals(PluginDevGate.Verdict.DEVELOPER_MODE_OFF, PluginDevGate.check(2000, developerMode = false))
    }

    @Test
    fun `root gets in like the shell`() {
        assertEquals(PluginDevGate.Verdict.ALLOWED, PluginDevGate.check(0, developerMode = true))
        assertEquals(PluginDevGate.Verdict.DEVELOPER_MODE_OFF, PluginDevGate.check(0, developerMode = false))
    }

    @Test
    fun `an app is refused whatever the mode, and is not told the mode`() {
        // An app's uid, the system's, a second user's shell-looking uid, and the keyboard's own.
        for (uid in listOf(10_123, 1000, 1_002_000, 10_000, -1)) {
            assertEquals("uid $uid", PluginDevGate.Verdict.NOT_ADB, PluginDevGate.check(uid, developerMode = true))
            assertEquals("uid $uid", PluginDevGate.Verdict.NOT_ADB, PluginDevGate.check(uid, developerMode = false))
        }
    }

    @Test
    fun `each refusal starts with the code wmpy reads`() {
        assertTrue(PluginDevGate.refusal(PluginDevGate.Verdict.NOT_ADB).startsWith("wmkb_not_adb:"))
        assertTrue(PluginDevGate.refusal(PluginDevGate.Verdict.DEVELOPER_MODE_OFF).startsWith("wmkb_dev_mode_off:"))
    }

    @Test
    fun `nothing reaches logcat in a process the provider never started`() {
        assertFalse(PluginDevMode.logsToSystem())
        // And a log with an id still keeps its lines.
        val log = PluginLog(null, pluginId = "com.example.demo")
        log.add("hello")
        assertEquals(listOf("hello"), log.lines())
    }

    @Test
    fun `log levels follow the line's prefix`() {
        assertEquals(android.util.Log.ERROR, PluginDevMode.priorityOf("error: main.py:3: boom"))
        assertEquals(android.util.Log.WARN, PluginDevMode.priorityOf("stopped: steps"))
        assertEquals(android.util.Log.WARN, PluginDevMode.priorityOf("abandoned: exceeded its time limit"))
        assertEquals(android.util.Log.INFO, PluginDevMode.priorityOf("count is 3"))
    }

    // ---- replies -------------------------------------------------------

    private fun plugin(permissions: List<String>, granted: List<String> = emptyList(), newSince: List<String> = emptyList()) = InstalledPlugin(
        id = "com.example.demo",
        name = "Demo",
        version = "1.2",
        apiVersion = 5,
        permissions = permissions,
        granted = granted,
        newSinceUpdate = newSince,
        language = PluginManifestCodec.LANGUAGE_PYTHON,
    )

    @Test
    fun `a fresh install names every risky permission it left off`() {
        val reply = PluginDevReply.parse(
            PluginDevReply.installed(plugin(listOf("storage", "network", "field.read")), replaced = false, reloaded = false, allowRisky = false),
        )
        assertTrue(reply["ok"]!!.jsonPrimitive.boolean)
        assertEquals("installed", reply["action"]!!.jsonPrimitive.content)
        assertEquals("com.example.demo", reply["id"]!!.jsonPrimitive.content)
        assertEquals("1.2", reply["version"]!!.jsonPrimitive.content)
        assertEquals(listOf("network", "field.read"), (reply["riskyOff"] as JsonArray).map { it.jsonPrimitive.content })
        assertFalse(reply["allowRisky"]!!.jsonPrimitive.boolean)
        assertEquals(PluginDevReply.PROTOCOL, reply["protocol"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an update keeps quiet about a permission the user granted`() {
        val reply = PluginDevReply.parse(
            PluginDevReply.installed(
                plugin(listOf("network", "ai"), granted = listOf("network"), newSince = listOf("ai")),
                replaced = true,
                reloaded = true,
                allowRisky = true,
            ),
        )
        assertEquals("updated", reply["action"]!!.jsonPrimitive.content)
        assertTrue(reply["reloaded"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("ai"), (reply["riskyOff"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf("ai"), (reply["newSinceUpdate"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun `status lists the plugins with their versions`() {
        val reply = PluginDevReply.parse(
            PluginDevReply.status(true, "0.6.6", 606L, pluginsOn = true, allowRisky = false, plugins = listOf(plugin(listOf("network")))),
        )
        assertTrue(reply["developerMode"]!!.jsonPrimitive.boolean)
        assertEquals(PluginManifestCodec.API_VERSION, reply["pluginApi"]!!.jsonPrimitive.int)
        val first = (reply["plugins"] as JsonArray).first() as kotlinx.serialization.json.JsonObject
        assertEquals("1.2", first["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an error reply carries its code and words`() {
        val reply = PluginDevReply.parse(PluginDevReply.error("plugins_off", "Allow plugins is off"))
        assertFalse(reply["ok"]!!.jsonPrimitive.boolean)
        assertEquals("plugins_off", reply["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a reply is one line whatever the log holds`() {
        val text = PluginDevReply.log("com.example.demo", listOf("a\nb", "}] {json=", "naïve"))
        assertFalse(text.contains('\n'))
        assertEquals(3, (PluginDevReply.parse(text)["lines"] as JsonArray).size)
    }

    // ---- uploads -------------------------------------------------------

    @Test
    fun `pieces add up to the archive, which is taken once`() {
        val uploads = PluginDevUploads()
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        val encoded = kotlin.io.encoding.Base64.encode(bytes)
        assertEquals(400, uploads.part("abcdefgh:" + kotlin.io.encoding.Base64.encode(bytes.copyOfRange(0, 400))))
        assertEquals(1000, uploads.part("abcdefgh:" + kotlin.io.encoding.Base64.encode(bytes.copyOfRange(400, 1000))))
        val taken = uploads.take("abcdefgh") as PluginDevUploads.Taken.Ok
        assertTrue(bytes.contentEquals(taken.bytes))
        assertEquals(PluginDevUploads.Taken.Unknown, uploads.take("abcdefgh"))
        assertTrue(encoded.isNotEmpty())
    }

    @Test
    fun `a bad token or bad base64 is not a piece`() {
        val uploads = PluginDevUploads()
        assertNull(uploads.part("short:AAAA"))
        assertNull(uploads.part("UPPERCASE1:AAAA"))
        assertNull(uploads.part("abcdefgh:not base64!"))
        assertNull(uploads.part("no-colon-at-all"))
        assertNull(uploads.begin("../../etc", viaPipe = true))
    }

    @Test
    fun `an archive past the cap is refused`() {
        val uploads = PluginDevUploads(maxBytes = 10)
        assertEquals(-1, uploads.part("abcdefgh:" + kotlin.io.encoding.Base64.encode(ByteArray(11))))
        assertEquals(PluginDevUploads.Taken.TooLarge, uploads.take("abcdefgh"))
    }

    @Test
    fun `a pipe is read to its end`() {
        val uploads = PluginDevUploads()
        val upload = uploads.begin("pipe0001", viaPipe = true)!!
        uploads.fromPipe(upload, ByteArrayInputStream("PK archive".toByteArray()))
        val taken = uploads.take("pipe0001", waitMillis = 10) as PluginDevUploads.Taken.Ok
        assertEquals("PK archive", taken.bytes.decodeToString())
    }

    @Test
    fun `a pipe still open is not taken`() {
        val uploads = PluginDevUploads()
        uploads.begin("pipe0002", viaPipe = true)
        assertEquals(PluginDevUploads.Taken.StillArriving, uploads.take("pipe0002", waitMillis = 10))
    }

    @Test
    fun `a pipe that fails part way is broken, not thrown`() {
        val uploads = PluginDevUploads()
        val upload = uploads.begin("pipe0003", viaPipe = true)!!
        val failing = object : InputStream() {
            var sent = false
            override fun read(): Int = throw IOException("remote died")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent) throw IOException("remote died")
                sent = true
                b[off] = 1
                return 1
            }
        }
        uploads.fromPipe(upload, failing)
        assertEquals(PluginDevUploads.Taken.Broken, uploads.take("pipe0003", waitMillis = 10))
    }

    @Test
    fun `only a few uploads wait at once`() {
        val uploads = PluginDevUploads(maxPending = 2)
        uploads.part("first001:AA==")
        uploads.part("second01:AA==")
        uploads.part("third001:AA==")
        assertEquals(PluginDevUploads.Taken.Unknown, uploads.take("first001"))
        assertNotNull(uploads.take("third001") as? PluginDevUploads.Taken.Ok)
    }

    // ---- the update hook -----------------------------------------------

    private fun manifest(version: String) = PluginManifest(
        format = PluginManifestCodec.FORMAT,
        version = PluginManifestCodec.VERSION,
        id = "com.example.demo",
        name = "Demo",
        pluginVersion = version,
        author = "Example",
        description = "A demo.",
        apiVersion = PluginManifestCodec.API_VERSION,
        entry = PluginManifestCodec.DEFAULT_ENTRY,
        permissions = emptyList(),
    )

    @Test
    fun `an update is announced outside the store's lock, a first install is not`() {
        val store = PluginStore(temp.newFolder("plugins"))
        val heard = ArrayList<String>()
        var locked = false
        store.addUpdateListener { id ->
            heard += id
            locked = locked || Thread.holdsLock(store)
            true
        }
        val first = store.adopt(manifest("1.0"), "x = 1") as PluginAdoptResult.Adopted
        assertTrue(heard.isEmpty())
        assertFalse(first.reloaded)

        val second = store.adopt(manifest("1.1"), "x = 2") as PluginAdoptResult.Adopted
        assertEquals(listOf("com.example.demo"), heard)
        assertTrue(second.replaced)
        assertTrue(second.reloaded)
        assertFalse("the listener ran under the store's lock", locked)
    }

    @Test
    fun `announcing says whether any panel had the plugin open`() {
        val store = PluginStore(temp.newFolder("plugins2"))
        assertFalse(store.announceUpdate("com.example.demo"))
        val closed: (String) -> Boolean = { false }
        val open: (String) -> Boolean = { it == "com.example.demo" }
        store.addUpdateListener(closed)
        assertFalse(store.announceUpdate("com.example.demo"))
        store.addUpdateListener(open)
        assertTrue(store.announceUpdate("com.example.demo"))
        assertFalse(store.announceUpdate("com.example.other"))
        store.removeUpdateListener(open)
        assertFalse(store.announceUpdate("com.example.demo"))
    }
}

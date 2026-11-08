package com.wasimaster.wmkeyboard.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): PluginStore = PluginStore(temp.newFolder("plugins-${counter++}"))

    private fun manifest(
        id: String = "com.example.demo",
        name: String = "Demo",
        version: String = "1.0.0",
        permissions: List<String> = emptyList(),
    ) = PluginManifest(
        format = PluginManifestCodec.FORMAT,
        version = PluginManifestCodec.VERSION,
        id = id,
        name = name,
        pluginVersion = version,
        author = "Example",
        description = "A demo.",
        apiVersion = PluginManifestCodec.API_VERSION,
        entry = PluginManifestCodec.DEFAULT_ENTRY,
        permissions = permissions,
    )

    // ---- adopt ---------------------------------------------------------

    @Test
    fun `adopting a plugin writes its manifest and script`() {
        val store = store()
        val result = store.adopt(manifest(), "x = 1") as PluginAdoptResult.Adopted
        assertFalse(result.replaced)
        assertEquals("Demo", result.plugin.name)
        assertEquals("x = 1", store.script("com.example.demo"))
        assertTrue(store.manifestFile("com.example.demo")!!.isFile)
    }

    @Test
    fun `an adopted plugin survives a reload`() {
        val dir = temp.newFolder("reload")
        PluginStore(dir).adopt(manifest(), "x = 1")
        val reopened = PluginStore(dir)
        assertEquals(1, reopened.plugins().size)
        assertEquals("Demo", reopened.plugin("com.example.demo")?.name)
    }

    @Test
    fun `updating a plugin replaces its script and keeps its storage`() {
        val store = store()
        store.adopt(manifest(version = "1.0.0", permissions = listOf("storage")), "old")
        store.storageFile("com.example.demo")!!.writeText("""{"todos":"[]"}""")

        val result = store.adopt(manifest(version = "2.0.0"), "new") as PluginAdoptResult.Adopted
        assertTrue(result.replaced)
        assertEquals("2.0.0", result.plugin.version)
        assertEquals("new", store.script("com.example.demo"))
        assertEquals(1, store.plugins().size)
        // The user's data outlives the plugin that owns it being upgraded.
        assertEquals("""{"todos":"[]"}""", store.storageFile("com.example.demo")!!.readText())
    }

    @Test
    fun `a permission dropped by an update stops applying`() {
        val store = store()
        store.adopt(manifest(permissions = listOf("storage")), "old")
        assertTrue(store.plugin("com.example.demo")!!.grants(PluginPermission.Storage))

        store.adopt(manifest(permissions = emptyList()), "new")
        assertFalse(store.plugin("com.example.demo")!!.grants(PluginPermission.Storage))
    }

    @Test
    fun `the plugin list is capped`() {
        val store = store()
        repeat(PluginStore.MAX_PLUGINS) { i ->
            val outcome = store.adopt(manifest(id = "com.example.p$i"), "x = $i")
            assertTrue(outcome is PluginAdoptResult.Adopted)
        }
        assertEquals(
            PluginAdoptResult.TooManyPlugins,
            store.adopt(manifest(id = "com.example.spill"), "x = 0"),
        )
        // An update of something already installed still goes through.
        assertTrue(store.adopt(manifest(id = "com.example.p0"), "v2") is PluginAdoptResult.Adopted)
    }

    // ---- delete and reconcile ------------------------------------------

    @Test
    fun `deleting a plugin takes its whole directory with it`() {
        val store = store()
        store.adopt(manifest(permissions = listOf("storage")), "x = 1")
        val dir = store.dirFor("com.example.demo")!!
        store.storageFile("com.example.demo")!!.writeText("{}")
        store.logFile("com.example.demo")!!.writeText("hello")

        store.delete("com.example.demo")
        assertFalse(dir.exists())
        assertTrue(store.plugins().isEmpty())
    }

    @Test
    fun `an index entry whose script has gone is reconciled away`() {
        val store = store()
        store.adopt(manifest(), "x = 1")
        store.scriptFile("com.example.demo")!!.delete()

        assertTrue(store.reconcile())
        assertTrue(store.plugins().isEmpty())
        assertFalse(store.reconcile())
    }

    // ---- enable and strikes --------------------------------------------

    @Test
    fun `a plugin that runs away twice is disabled`() {
        val store = store()
        store.adopt(manifest(), "x = 1")

        assertTrue(store.recordAbandon("com.example.demo")!!.enabled)
        val second = store.recordAbandon("com.example.demo")!!
        assertEquals(PluginStore.MAX_ABANDONS, second.abandonedCount)
        assertFalse(second.enabled)
        assertTrue(store.runnablePlugins().isEmpty())
    }

    @Test
    fun `turning a disabled plugin back on forgives its strikes`() {
        val store = store()
        store.adopt(manifest(), "x = 1")
        repeat(PluginStore.MAX_ABANDONS) { store.recordAbandon("com.example.demo") }

        store.setEnabled("com.example.demo", true)
        val plugin = store.plugin("com.example.demo")!!
        assertTrue(plugin.enabled)
        assertEquals(0, plugin.abandonedCount)
    }

    // ---- direct boot ---------------------------------------------------

    @Test
    fun `a locked store is empty rather than broken`() {
        val store = PluginStore(null)
        assertTrue(store.plugins().isEmpty())
        assertNull(store.dirFor("com.example.demo"))
        assertNull(store.script("com.example.demo"))
        assertEquals(PluginAdoptResult.Failed, store.adopt(manifest(), "x = 1"))
        assertFalse(store.reconcile())
        store.delete("com.example.demo")
    }

    @Test
    fun `attaching after unlock picks up what is on disk`() {
        val dir = temp.newFolder("unlock")
        PluginStore(dir).adopt(manifest(), "x = 1")

        val store = PluginStore(null)
        assertTrue(store.plugins().isEmpty())
        store.attach(dir)
        assertEquals(1, store.plugins().size)
    }

    // ---- path safety ---------------------------------------------------

    @Test
    fun `an id that is not a safe path segment never becomes a directory`() {
        val store = store()
        for (bad in listOf("..", "../evil", "a/b", "a\\b", "", "UPPER", "ab")) {
            assertNull("expected $bad to resolve to nothing", store.dirFor(bad))
        }
        assertNotNull(store.dirFor("com.example.demo"))
    }

    @Test
    fun `an index hand-edited to carry an unsafe id is dropped on load`() {
        val dir = temp.newFolder("tampered")
        File(dir, "plugins.json").writeText(
            """{"version":1,"plugins":[{"id":"../escape","name":"Evil","version":"1.0.0"}]}""",
        )
        assertTrue(PluginStore(dir).plugins().isEmpty())
    }

    // ---- retiring Lua plugins (index format 2) ----------------------------

    /** A store directory as a Lua-era build left it: a version 1 index and each plugin's files. */
    private fun luaEra(dir: File, vararg plugins: Pair<String, String>): File {
        File(dir, "plugins.json").writeText(
            """{"version":1,"subsystemEnabled":true,"plugins":[${plugins.joinToString(",") { it.second }}]}""",
        )
        for ((id, _) in plugins) {
            File(dir, id).mkdirs()
            File(dir, "$id/plugin.json").writeText("{}")
            File(dir, "$id/main.lua").writeText("function render() return ui.label { text = 'old' } end")
        }
        return dir
    }

    private val todoRecord = """{"id":"$TODO","name":"Todo List","version":"2.1.0","apiVersion":2,"permissions":["storage","reminders"]}"""

    private val todoData = """{"items":"[{\"id\":1,\"text\":\"Buy milk\",\"done\":false,\"remind\":false}]"}"""

    private fun parkedDir(dir: File, id: String) = File(dir, ".parked/$id")

    @Test
    fun `a Lua plugin is retired on upgrade and its data is parked`() {
        val dir = luaEra(temp.newFolder(), TODO to todoRecord)
        File(dir, "$TODO/storage.json").writeText(todoData)
        File(dir, "$TODO/reminders.json").writeText("[]")

        val store = PluginStore(dir) { PARKED_AT }

        assertTrue(store.plugins().isEmpty())
        assertFalse(File(dir, TODO).exists())
        assertEquals(todoData, store.parkedStorageFile(TODO)!!.readText())
        assertEquals(listOf(ParkedPlugin(TODO, "Todo List", PARKED_AT, todoData.length.toLong())), store.parked())
        assertEquals(listOf("Todo List"), store.retiredNames())
        // Everything else in the index is kept, and the index is rewritten in the new format.
        assertTrue(store.subsystemEnabled())
        assertTrue(File(dir, "plugins.json").readText().contains("\"version\":2"))
    }

    @Test
    fun `the notice outlives a restart until it is dismissed`() {
        val dir = luaEra(temp.newFolder(), TODO to todoRecord)
        File(dir, "$TODO/storage.json").writeText(todoData)
        PluginStore(dir) { PARKED_AT }

        val reopened = PluginStore(dir) { PARKED_AT + 1_000 }
        assertEquals(listOf("Todo List"), reopened.retiredNames())
        assertEquals(PARKED_AT, reopened.parked().single().parkedAt)

        reopened.dismissRetired()
        assertTrue(reopened.retiredNames().isEmpty())
        val again = PluginStore(dir) { PARKED_AT + 2_000 }
        assertTrue(again.retiredNames().isEmpty())
        assertEquals(1, again.parked().size)
    }

    @Test
    fun `a Lua plugin with no data is retired with nothing parked`() {
        val dir = luaEra(
            temp.newFolder(),
            "com.example.cipher" to """{"id":"com.example.cipher","name":"","version":"1.0.0"}""",
        )
        val store = PluginStore(dir)
        assertTrue(store.plugins().isEmpty())
        assertTrue(store.parked().isEmpty())
        assertFalse(parkedDir(dir, "com.example.cipher").exists())
        // A record with no name is named by its id in the notice.
        assertEquals(listOf("com.example.cipher"), store.retiredNames())
    }

    @Test
    fun `installing the Python version brings the parked data back`() {
        val dir = luaEra(temp.newFolder(), TODO to todoRecord)
        File(dir, "$TODO/storage.json").writeText(todoData)
        val store = PluginStore(dir) { PARKED_AT }

        val adopted = store.adopt(manifest(id = TODO, name = "Todo List", permissions = listOf("storage")), "x = 1")
        assertTrue(adopted is PluginAdoptResult.Adopted)
        assertEquals(todoData, store.storageFile(TODO)!!.readText())
        assertTrue(store.parked().isEmpty())
        assertNull(store.parkedStorageFile(TODO))
        assertFalse(parkedDir(dir, TODO).exists())
        // And it stays that way after a restart.
        val reopened = PluginStore(dir) { PARKED_AT }
        assertEquals(todoData, reopened.storageFile(TODO)!!.readText())
        assertTrue(reopened.parked().isEmpty())
    }

    @Test
    fun `another plugin's install leaves parked data alone`() {
        val dir = luaEra(temp.newFolder(), TODO to todoRecord)
        File(dir, "$TODO/storage.json").writeText(todoData)
        val store = PluginStore(dir) { PARKED_AT }

        store.adopt(manifest(id = "com.example.other"), "x = 1")
        assertEquals(todoData, store.parkedStorageFile(TODO)!!.readText())
        assertEquals(1, store.parked().size)
    }

    @Test
    fun `parked data is purged after its days are up`() {
        val dir = luaEra(temp.newFolder(), TODO to todoRecord)
        File(dir, "$TODO/storage.json").writeText(todoData)
        PluginStore(dir) { PARKED_AT }

        val day = 24L * 60 * 60 * 1000
        val almost = PluginStore(dir) { PARKED_AT + PluginStore.PARKED_DAYS * day }
        assertEquals(1, almost.parked().size)

        val past = PluginStore(dir) { PARKED_AT + PluginStore.PARKED_DAYS * day + 1 }
        assertTrue(past.parked().isEmpty())
        assertNull(past.parkedStorageFile(TODO))
        assertFalse(parkedDir(dir, TODO).exists())
        assertTrue(PluginStore(dir) { PARKED_AT }.parked().isEmpty())
    }

    @Test
    fun `parked data can be thrown away by hand`() {
        val dir = luaEra(temp.newFolder(), TODO to todoRecord)
        File(dir, "$TODO/storage.json").writeText(todoData)
        val store = PluginStore(dir) { PARKED_AT }

        store.deleteParked(TODO)
        assertTrue(store.parked().isEmpty())
        assertFalse(parkedDir(dir, TODO).exists())
        assertTrue(PluginStore(dir) { PARKED_AT }.parked().isEmpty())
    }

    @Test
    fun `a parked entry whose file has gone is dropped`() {
        val dir = luaEra(temp.newFolder(), TODO to todoRecord)
        File(dir, "$TODO/storage.json").writeText(todoData)
        PluginStore(dir) { PARKED_AT }
        parkedDir(dir, TODO).deleteRecursively()

        assertTrue(PluginStore(dir) { PARKED_AT }.parked().isEmpty())
    }

    @Test
    fun `a Python plugin from before the rename keeps running from main py`() {
        val dir = temp.newFolder()
        val record = """{"id":"clink.reverse","name":"Reverse","version":"#abc","apiVersion":3,"language":"python",""" +
            """"panel":false,"permissions":["selection"],"actions":[{"id":"transform","name":"Reverse"}]}"""
        luaEra(dir, "clink.reverse" to record)
        File(dir, "clink.reverse/main.lua").writeText("def transform(text):\n    return text[::-1]")

        val store = PluginStore(dir)
        assertEquals("Reverse", store.plugin("clink.reverse")!!.name)
        assertEquals("def transform(text):\n    return text[::-1]", store.script("clink.reverse"))
        assertTrue(store.scriptFile("clink.reverse")!!.isFile)
        assertFalse(File(dir, "clink.reverse/main.lua").exists())
        assertTrue(store.retiredNames().isEmpty())
        assertFalse(store.reconcile())
    }

    @Test
    fun `a Lua and a Python plugin side by side are sorted apart`() {
        val dir = luaEra(
            temp.newFolder(),
            TODO to todoRecord,
            "clink.shrug" to """{"id":"clink.shrug","name":"Shrug","version":"1","language":"python","panel":false}""",
        )
        val store = PluginStore(dir)
        assertEquals(listOf("clink.shrug"), store.plugins().map { it.id })
        assertEquals(listOf("Todo List"), store.retiredNames())
    }

    @Test
    fun `an index already in the new format is read as it is`() {
        val dir = temp.newFolder()
        PluginStore(dir).adopt(manifest(), "x = 1")
        val before = File(dir, "plugins.json").readText()
        val store = PluginStore(dir)
        assertEquals(1, store.plugins().size)
        assertTrue(store.retiredNames().isEmpty())
        assertEquals(before, File(dir, "plugins.json").readText())
    }

    private companion object {
        var counter = 0
        const val TODO = "com.wasimaster.todo"
        const val PARKED_AT = 1_800_000_000_000L
    }
}

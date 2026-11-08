package com.wasimaster.wmkeyboard.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [PluginStore.adopt] as the plugin editor's publish uses it: `keepState`, and writes that cannot half-land. */
class PluginStoreWriteBackTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val id = "com.example.demo"

    private fun store(): PluginStore = PluginStore(temp.newFolder())

    private fun manifest(version: String = "1.0.0", permissions: List<String> = emptyList()) = PluginManifest(
        format = PluginManifestCodec.FORMAT,
        version = PluginManifestCodec.VERSION,
        id = id,
        name = "Demo",
        pluginVersion = version,
        author = "Example",
        description = "A demo.",
        apiVersion = PluginManifestCodec.API_VERSION,
        entry = PluginManifestCodec.DEFAULT_ENTRY,
        permissions = permissions,
    )

    @Test
    fun `publishing keeps a plugin the user switched off switched off`() {
        val store = store()
        store.adopt(manifest(), "v1")
        store.setEnabled(id, false)

        val result = store.adopt(manifest(version = "1.1.0"), "v2", keepState = true) as PluginAdoptResult.Adopted

        assertFalse(result.plugin.enabled)
        assertFalse(store.plugin(id)!!.enabled)
        assertEquals("1.1.0", result.plugin.version)
        assertEquals("v2", store.script(id))
    }

    @Test
    fun `publishing keeps the strike count`() {
        val store = store()
        store.adopt(manifest(), "v1")
        store.recordAbandon(id)

        val result = store.adopt(manifest(), "v2", keepState = true) as PluginAdoptResult.Adopted

        assertEquals(1, result.plugin.abandonedCount)
        assertTrue(result.plugin.enabled)
    }

    @Test
    fun `an ordinary install still resets the flag and the strikes`() {
        val store = store()
        store.adopt(manifest(), "v1")
        store.recordAbandon(id)
        store.setEnabled(id, false)

        val result = store.adopt(manifest(), "v2") as PluginAdoptResult.Adopted

        assertTrue(result.plugin.enabled)
        assertEquals(0, result.plugin.abandonedCount)
    }

    @Test
    fun `keepState on a first install behaves like any install`() {
        val result = store().adopt(manifest(), "v1", keepState = true) as PluginAdoptResult.Adopted
        assertTrue(result.plugin.enabled)
        assertEquals(0, result.plugin.abandonedCount)
        assertFalse(result.replaced)
    }

    @Test
    fun `both kinds of adopt keep the plugin's stored data`() {
        val store = store()
        store.adopt(manifest(permissions = listOf("storage")), "v1")
        val storage = store.storageFile(id)!!
        storage.writeText("""{"k":"v"}""")

        store.adopt(manifest(permissions = listOf("storage")), "v2", keepState = true)
        assertEquals("""{"k":"v"}""", storage.readText())
        store.adopt(manifest(permissions = listOf("storage")), "v3")
        assertEquals("""{"k":"v"}""", storage.readText())
    }

    @Test
    fun `a write leaves no part files behind`() {
        val store = store()
        store.adopt(manifest(), "v1")
        store.adopt(manifest(), "v2", keepState = true)
        assertTrue(store.dirFor(id)!!.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun `a short script written over a long one leaves no tail`() {
        val store = store()
        store.adopt(manifest(), "x".repeat(10_000))
        store.adopt(manifest(), "short", keepState = true)
        assertEquals("short", store.script(id))
        assertEquals(5L, store.scriptFile(id)!!.length())
    }

    @Test
    fun `a locked store refuses to publish`() {
        assertEquals(PluginAdoptResult.Failed, PluginStore(null).adopt(manifest(), "v1", keepState = true))
    }
}

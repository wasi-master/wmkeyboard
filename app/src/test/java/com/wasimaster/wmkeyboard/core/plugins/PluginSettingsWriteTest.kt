package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `secret` settings rows and `plugin.settings.set`: what the manifest
 * accepts, where a secret is kept, what a plugin may write, and who hears a
 * change.
 */
class PluginSettingsWriteTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val rows = listOf(
        PluginSettingSpec(key = "units", type = PluginSettingSpec.TYPE_CHOICE, label = "Units", default = "metric", options = listOf("metric", "imperial")),
        PluginSettingSpec(key = "count", type = PluginSettingSpec.TYPE_NUMBER, label = "Count", default = "5"),
        PluginSettingSpec(key = "loud", type = PluginSettingSpec.TYPE_SWITCH, label = "Loud", default = "false"),
        PluginSettingSpec(key = "server", type = PluginSettingSpec.TYPE_HOST, label = "Server"),
        PluginSettingSpec(key = "token", type = PluginSettingSpec.TYPE_SECRET, label = "Token"),
    )

    private fun manifest(permissions: List<String> = listOf("storage"), settings: List<PluginSettingSpec> = rows) = PluginManifest(
        format = PluginManifestCodec.FORMAT,
        id = "com.example.settings",
        name = "Settings",
        pluginVersion = "1.0",
        apiVersion = 5,
        permissions = permissions,
        language = PluginManifestCodec.LANGUAGE_PYTHON,
        settings = settings,
    )

    private fun installed(store: PluginStore): InstalledPlugin =
        (store.adopt(manifest(), "x = 1") as PluginAdoptResult.Adopted).plugin

    private fun set(store: PluginStore, key: String, value: kotlinx.serialization.json.JsonElement?) =
        PluginHost.setSetting(store, store.plugin("com.example.settings")!!, buildJsonObject { put("key", key); value?.let { put("value", it) } })

    private fun refusedWith(kind: String, body: () -> Unit) {
        try {
            body()
            fail("expected $kind")
        } catch (e: PluginCallException) {
            assertEquals(kind, e.kind)
        }
    }

    @Test
    fun `a secret row needs storage`() {
        assertTrue(PluginManifestCodec.problems(manifest()).isEmpty())
        assertFalse(PluginManifestCodec.problems(manifest(permissions = emptyList())).isEmpty())
    }

    @Test
    fun `values must fit their row`() {
        val units = rows[0]
        assertEquals("imperial", units.storedText(JsonPrimitive("imperial")))
        assertNull(units.storedText(JsonPrimitive("kelvin")))
        assertEquals("7", rows[1].storedText(JsonPrimitive(7)))
        assertEquals("2.5", rows[1].storedText(JsonPrimitive(2.5)))
        assertNull(rows[1].storedText(JsonPrimitive("7")))
        assertEquals("true", rows[2].storedText(JsonPrimitive(true)))
        assertNull(rows[2].storedText(JsonPrimitive("true")))
        assertNull(rows[3].storedText(JsonPrimitive("https://evil.example")))
    }

    @Test
    fun `a secret is kept in secrets, never in the session config or plugins json`() {
        val store = PluginStore(temp.newFolder())
        installed(store)
        store.setSettingValue("com.example.settings", "token", "abc")
        assertEquals("abc", store.secrets("com.example.settings")!!.get("token"))
        val plugin = store.plugin("com.example.settings")!!
        assertFalse("token" in plugin.settingValues)
        assertEquals(PluginSettingSpec.SECRET_MARKER, plugin.settingsJson()["token"])
        store.setSettingValue("com.example.settings", "token", null)
        assertNull(store.secrets("com.example.settings")!!.get("token"))
    }

    @Test
    fun `a plugin writes its own rows and gets the value back typed`() {
        val store = PluginStore(temp.newFolder())
        installed(store)
        assertEquals(JsonPrimitive("imperial"), set(store, "units", JsonPrimitive("imperial")))
        assertEquals(JsonPrimitive(9L), set(store, "count", JsonPrimitive(9)))
        assertEquals(JsonPrimitive(true), set(store, "loud", JsonPrimitive(true)))
        assertEquals("imperial", store.plugin("com.example.settings")!!.settingValues["units"])
        // None puts the default back.
        assertEquals(JsonPrimitive("metric"), set(store, "units", JsonNull))
        assertEquals(PluginSettingSpec.SECRET_MARKER, set(store, "token", JsonPrimitive("t0k")))
        assertEquals("t0k", store.secrets("com.example.settings")!!.get("token"))
    }

    @Test
    fun `a plugin cannot write a host row, an unknown key or a mismatched value`() {
        val store = PluginStore(temp.newFolder())
        installed(store)
        refusedWith("PermissionError") { set(store, "server", JsonPrimitive("https://evil.example")) }
        refusedWith("ValueError") { set(store, "nope", JsonPrimitive(1)) }
        refusedWith("ValueError") { set(store, "units", JsonPrimitive("kelvin")) }
        refusedWith("ValueError") { set(store, "count", JsonPrimitive("many")) }
        assertTrue(store.plugin("com.example.settings")!!.allowedHosts.isEmpty())
    }

    @Test
    fun `listeners hear the user's changes, not the plugin's own`() {
        val store = PluginStore(temp.newFolder())
        installed(store)
        val heard = ArrayList<String>()
        val listener: (String, String) -> Unit = { _, key -> heard += key }
        store.addSettingListener(listener)
        store.setSettingValue("com.example.settings", "units", "imperial")
        set(store, "count", JsonPrimitive(3))
        store.setSettingValue("com.example.settings", "units", "imperial") // unchanged
        store.removeSettingListener(listener)
        store.setSettingValue("com.example.settings", "units", "metric")
        assertEquals(listOf("units"), heard)
    }

    @Test
    fun `the session reads a secret row as a marker`() {
        val marker = PluginSettingSpec.SECRET_MARKER
        assertTrue(marker is JsonObject)
        assertEquals(JsonPrimitive(true), marker["\$secret"])
    }
}

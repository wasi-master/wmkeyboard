package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The keyboard's own check on every API 5 host call: what is refused before
 * any service sees it, and why. No interpreter here: [PluginHost.call] is
 * driven directly, as the library would.
 */
class PluginHostTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val handle = 4242L
    private val calls = ArrayList<String>()

    private val services = object : PluginServices {
        var shielded = false
        var saver = false

        override fun call(call: PluginCall): JsonElement {
            calls += call.op
            return JsonPrimitive("done")
        }

        override fun fieldProtected(): Boolean = shielded

        override fun dataSaver(): Boolean = saver
    }

    @After
    fun unbind() = PluginHost.unbind(handle)

    private fun plugin(
        permissions: List<String>,
        granted: List<String> = emptyList(),
        hosts: List<String> = listOf("api.example.com", "*.weather.example"),
    ) = InstalledPlugin(
        id = "com.example.host",
        name = "Host",
        version = "1",
        apiVersion = 5,
        permissions = permissions,
        language = PluginManifestCodec.LANGUAGE_PYTHON,
        granted = granted,
        hosts = hosts,
    )

    private fun bind(plugin: InstalledPlugin, allowRisky: Boolean = true, userEvent: Boolean = true): PluginHost.Binding {
        val store = PluginStore(temp.newFolder()).apply { setAllowRisky(allowRisky) }
        return PluginHost.Binding(plugin, store, services, "panel") {}.also {
            it.userEvent = userEvent
            PluginHost.bind(handle, it)
        }
    }

    private fun call(op: String, args: String = "{}"): JsonObject =
        Json.parseToJsonElement(PluginHost.call(handle, op, args)).jsonObject

    private fun JsonObject.errorKind(): String? = (this["error"] as? JsonObject)?.get("kind")?.jsonPrimitive?.content

    private fun JsonObject.needs(): String? = (this["error"] as? JsonObject)?.get("needs")?.jsonPrimitive?.content

    @Test
    fun `a granted call reaches the service`() {
        bind(plugin(listOf("field.write")))
        val reply = call("field.insert", """{"text": "hi"}""")
        assertEquals("done", reply["value"]?.jsonPrimitive?.content)
        assertEquals(listOf("field.insert"), calls)
    }

    @Test
    fun `an undeclared permission is refused`() {
        bind(plugin(emptyList()))
        assertEquals("PermissionError", call("field.insert").errorKind())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a risky permission that was not granted is refused and named`() {
        bind(plugin(listOf("network")))
        val reply = call("http.request", """{"url": "https://api.example.com/x"}""")
        assertEquals("PermissionError", reply.errorKind())
        assertEquals("network", reply.needs())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `the global switch stops every risky call`() {
        bind(plugin(listOf("network"), granted = listOf("network")), allowRisky = false)
        assertEquals("PermissionError", call("http.request", """{"url": "https://api.example.com/x"}""").errorKind())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `password fields and incognito refuse field and clipboard calls`() {
        bind(plugin(listOf("field.read", "field.write", "clipboard.read"), granted = listOf("field.read", "clipboard.read")))
        services.shielded = true
        for (op in listOf("field.insert", "field.selection", "clipboard.get")) {
            assertEquals(op, "PermissionError", call(op).errorKind())
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `reading the field needs the user to be acting in the panel`() {
        val binding = bind(plugin(listOf("field.read"), granted = listOf("field.read")), userEvent = false)
        assertEquals("PermissionError", call("field.selection").errorKind())
        binding.userEvent = true
        assertNull(call("field.selection").errorKind())
    }

    @Test
    fun `requests go only to listed https hosts`() {
        bind(plugin(listOf("network"), granted = listOf("network")))
        assertNull(call("http.request", """{"url": "https://api.example.com/v1"}""").errorKind())
        assertNull(call("http.request", """{"url": "https://de.weather.example/today"}""").errorKind())
        for (url in listOf(
            "http://api.example.com/v1",
            "https://evil.example.org/",
            "https://127.0.0.1/",
            "https://[::1]/",
            "https://localhost/",
            "https://user@api.example.com/",
            "ftp://api.example.com/",
        )) {
            assertFalse(url, call("http.request", """{"url": "$url"}""").errorKind() == null)
        }
        assertEquals(2, calls.size)
    }

    @Test
    fun `data saver stops requests`() {
        bind(plugin(listOf("network"), granted = listOf("network")))
        services.saver = true
        assertEquals("ConnectionError", call("http.request", """{"url": "https://api.example.com/"}""").errorKind())
    }

    @Test
    fun `a closed session answers with an error`() {
        assertEquals("RuntimeError", call("ui.toast").errorKind())
    }

    @Test
    fun `host names are read the way the check needs them`() {
        assertEquals("api.example.com", PluginHost.requestHost("https://API.example.com:443/x?y=1"))
        assertNull(PluginHost.requestHost("https://10.0.0.1/"))
        assertNull(PluginHost.requestHost("not a url"))
        assertTrue(PluginManifestCodec.hostAllowed("a.b.example.com", listOf("*.example.com")))
        assertTrue(PluginManifestCodec.hostAllowed("example.com", listOf("*.example.com")))
        assertFalse(PluginManifestCodec.hostAllowed("badexample.com", listOf("*.example.com")))
    }

    @Test
    fun `every op maps to the permission the library checks`() {
        assertEquals(PluginPermission.Network, PluginPermission.forOp("http.request"))
        assertEquals(PluginPermission.FieldRead, PluginPermission.forOp("field.before"))
        assertEquals(PluginPermission.FieldWrite, PluginPermission.forOp("field.insert"))
        assertEquals(PluginPermission.ClipboardRead, PluginPermission.forOp("clipboard.get"))
        assertEquals(PluginPermission.ClipboardWrite, PluginPermission.forOp("clipboard.set"))
        assertEquals(PluginPermission.Storage, PluginPermission.forOp("secrets.get"))
        assertNull(PluginPermission.forOp("ui.toast"))
    }

    @Test
    fun `reading something and sending it away is the combination warned about`() {
        assertTrue(PluginPermission.combinationRisk(listOf(PluginPermission.Network, PluginPermission.FieldRead)))
        assertTrue(PluginPermission.combinationRisk(listOf(PluginPermission.Ai, PluginPermission.SelectedText)))
        assertFalse(PluginPermission.combinationRisk(listOf(PluginPermission.Network, PluginPermission.Storage)))
    }
}

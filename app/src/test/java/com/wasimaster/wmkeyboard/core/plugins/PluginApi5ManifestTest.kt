package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** API 5 manifests, archives and the grants an install and an update leave. */
class PluginApi5ManifestTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun manifest(
        apiVersion: Int = 5,
        permissions: List<String> = emptyList(),
        hosts: List<String> = emptyList(),
        tool: PluginToolSpec = PluginToolSpec(),
        settings: List<PluginSettingSpec> = emptyList(),
    ) = PluginManifest(
        format = PluginManifestCodec.FORMAT,
        id = "com.example.weather",
        name = "Weather",
        pluginVersion = "1.0",
        apiVersion = apiVersion,
        permissions = permissions,
        language = PluginManifestCodec.LANGUAGE_PYTHON,
        hosts = hosts,
        tool = tool,
        settings = settings,
    )

    private fun problems(m: PluginManifest) = PluginManifestCodec.problems(m).map { it.text }

    @Test
    fun `a network plugin with hosts is accepted`() {
        assertTrue(problems(manifest(permissions = listOf("network"), hosts = listOf("api.open-meteo.com", "*.example.com"))).isEmpty())
    }

    @Test
    fun `network without hosts is refused`() {
        assertEquals(
            listOf(PluginText.of(R.string.core_plugins_reject_network_without_hosts, "Weather")),
            problems(manifest(permissions = listOf("network"))),
        )
    }

    @Test
    fun `a host setting stands in for listed hosts`() {
        val setting = PluginSettingSpec(key = "server", type = PluginSettingSpec.TYPE_HOST, label = "Server")
        assertTrue(problems(manifest(permissions = listOf("network"), settings = listOf(setting))).isEmpty())
    }

    @Test
    fun `addresses, ports and localhost are not hosts`() {
        for (bad in listOf("127.0.0.1", "localhost", "example.com:8080", "*.com.", "exa mple.com", "*", "http://x")) {
            assertFalse(bad, problems(manifest(permissions = listOf("network"), hosts = listOf(bad))).isEmpty())
        }
    }

    @Test
    fun `API 5 permissions need API 5`() {
        val text = problems(manifest(apiVersion = 4, permissions = listOf("field.write"))).single()
        assertEquals(PluginText.of(R.string.core_plugins_reject_permission_needs_api_5, "field.write", "Weather"), text)
    }

    @Test
    fun `settings must be drawable`() {
        val good = PluginSettingSpec(key = "units", type = "choice", label = "Units", options = listOf("metric", "imperial"))
        assertTrue(problems(manifest(settings = listOf(good))).isEmpty())
        val bad = listOf(
            good.copy(type = "colour"),
            good.copy(options = emptyList()),
            good.copy(key = "Has Space"),
            good.copy(label = " "),
        )
        for (setting in bad) assertFalse(setting.toString(), problems(manifest(settings = listOf(setting))).isEmpty())
        assertFalse(problems(manifest(settings = listOf(good, good))).isEmpty())
    }

    @Test
    fun `a tool keyword is short and plain`() {
        assertTrue(problems(manifest(tool = PluginToolSpec(keyword = "weather", tile = true, icon = "cloud"))).isEmpty())
        assertFalse(problems(manifest(tool = PluginToolSpec(keyword = "Weather Now"))).isEmpty())
    }

    @Test
    fun `an archive carries modules and assets`() {
        val out = ByteArrayOutputStream()
        val m = manifest()
        PluginFile.write(
            out,
            m,
            "from wm import ui\nimport helpers",
            modules = mapOf("helpers.py" to "X = 1", "lib/__init__.py" to ""),
            assets = mapOf("logo.png" to byteArrayOf(1, 2, 3)),
        )
        val read = PluginFile.read(ByteArrayInputStream(out.toByteArray())) as PluginReadResult.Ok
        assertEquals(setOf("helpers.py", "lib/__init__.py"), read.modules.keys)
        assertEquals(listOf<Byte>(1, 2, 3), read.assets["logo.png"]?.toList())

        val store = PluginStore(temp.newFolder())
        PluginFile.import(ByteArrayInputStream(out.toByteArray()), store)
        assertEquals("X = 1", store.modules(m.id)["helpers.py"])
        assertEquals(3, store.asset(m.id, "logo.png")?.size)
    }

    @Test
    fun `a module that cannot be imported refuses the install`() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(PluginFile.MANIFEST))
            zip.write(PluginManifestCodec.encode(manifest()).toByteArray())
            zip.putNextEntry(java.util.zip.ZipEntry("main.py"))
            zip.write("x = 1".toByteArray())
            zip.putNextEntry(java.util.zip.ZipEntry("my helpers.py"))
            zip.write("y = 2".toByteArray())
        }
        assertTrue(PluginFile.read(ByteArrayInputStream(out.toByteArray())) is PluginReadResult.Rejected)
    }

    @Test
    fun `risky permissions install off and are granted one by one`() {
        val store = PluginStore(temp.newFolder())
        val m = manifest(permissions = listOf("network", "storage"), hosts = listOf("api.example.com"))
        val plugin = (store.adopt(m, "x = 1") as PluginAdoptResult.Adopted).plugin
        assertFalse(plugin.grants(PluginPermission.Network))
        assertTrue(plugin.grants(PluginPermission.Storage))
        store.setGranted(m.id, PluginPermission.Network, true)
        assertTrue(store.plugin(m.id)!!.grants(PluginPermission.Network))
        // A listed permission has nothing to grant.
        store.setGranted(m.id, PluginPermission.Storage, false)
        assertTrue(store.plugin(m.id)!!.grants(PluginPermission.Storage))
    }

    @Test
    fun `an update that adds a host takes the network grant back`() {
        val store = PluginStore(temp.newFolder())
        val m = manifest(permissions = listOf("network"), hosts = listOf("api.example.com"))
        store.adopt(m, "x = 1")
        store.setGranted(m.id, PluginPermission.Network, true)

        store.adopt(m.copy(pluginVersion = "1.1"), "x = 2")
        assertTrue("same hosts keep the grant", store.plugin(m.id)!!.grants(PluginPermission.Network))

        store.adopt(m.copy(pluginVersion = "1.2", hosts = listOf("api.example.com", "tracker.example")), "x = 3")
        val updated = store.plugin(m.id)!!
        assertFalse(updated.grants(PluginPermission.Network))
        assertTrue("tracker.example" in updated.newSinceUpdate)
    }

    @Test
    fun `the risky switch is off until turned on and survives a reload`() {
        val dir = temp.newFolder()
        val store = PluginStore(dir)
        assertFalse(store.allowRisky())
        store.setAllowRisky(true)
        assertTrue(PluginStore(dir).allowRisky())
    }

    @Test
    fun `settings values are typed for the script`() {
        val plugin = InstalledPlugin(
            settings = listOf(
                PluginSettingSpec(key = "on", type = "switch", label = "On", default = "true"),
                PluginSettingSpec(key = "days", type = "number", label = "Days", default = "3"),
                PluginSettingSpec(key = "units", type = "choice", label = "Units", default = "metric", options = listOf("metric")),
            ),
            settingValues = mapOf("days" to "7"),
        )
        val json = plugin.settingsJson().toString()
        assertEquals("""{"on":true,"days":7,"units":"metric"}""", json)
    }
}

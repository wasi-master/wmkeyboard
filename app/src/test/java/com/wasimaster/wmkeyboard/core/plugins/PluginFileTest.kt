package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): PluginStore = PluginStore(temp.newFolder("plugins-${counter++}"))

    private fun manifest(
        id: String = "com.example.demo",
        entry: String = "main.py",
        permissions: List<String> = emptyList(),
    ) = PluginManifest(
        format = PluginManifestCodec.FORMAT,
        version = PluginManifestCodec.VERSION,
        id = id,
        name = "Demo",
        pluginVersion = "1.0.0",
        author = "Example",
        description = "A demo.",
        apiVersion = PluginManifestCodec.API_VERSION,
        entry = entry,
        permissions = permissions,
    )

    private fun archive(manifest: PluginManifest, script: String): ByteArray =
        ByteArrayOutputStream().also { PluginFile.write(it, manifest, script) }.toByteArray()

    /** A ZIP built by hand, for the shapes [PluginFile.write] would never produce. */
    private fun rawArchive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A manifest written by hand, for the fields [PluginManifestCodec.encode] always writes. */
    private fun rawManifest(apiVersion: Int, entry: String?, language: String? = null): String = buildString {
        append("""{"format": "${PluginManifestCodec.FORMAT}", "version": 1, "id": "com.example.demo", "name": "Demo",""")
        append(""" "pluginVersion": "1.0.0", "apiVersion": $apiVersion, "permissions": []""")
        if (entry != null) append(""", "entry": "$entry"""")
        if (language != null) append(""", "language": "$language"""")
        append("}")
    }

    private fun import(bytes: ByteArray, store: PluginStore) =
        PluginFile.import(ByteArrayInputStream(bytes), store)

    // ---- round trip ----------------------------------------------------

    @Test
    fun `a written plugin imports back`() {
        val store = store()
        val result = import(archive(manifest(), "x = 42"), store) as PluginImportResult.Imported
        assertFalse(result.replaced)
        assertEquals("com.example.demo", result.plugin.id)
        assertEquals("x = 42", store.script("com.example.demo"))
    }

    @Test
    fun `importing the same plugin twice replaces it`() {
        val store = store()
        import(archive(manifest(), "v1"), store)
        val second = import(archive(manifest(), "v2"), store) as PluginImportResult.Imported
        assertTrue(second.replaced)
        assertEquals(1, store.plugins().size)
        assertEquals("v2", store.script("com.example.demo"))
    }

    @Test
    fun `a script under a directory is still found by its name`() {
        val store = store()
        val bytes = rawArchive(
            PluginFile.MANIFEST to PluginManifestCodec.encode(manifest()).toByteArray(),
            "src/main.py" to "x = 1".toByteArray(),
        )
        assertTrue(import(bytes, store) is PluginImportResult.Imported)
    }

    @Test
    fun `reading the manifest never touches the script`() {
        val bytes = archive(manifest(permissions = listOf("storage")), "raise ValueError(\"should not run\")")
        val read = PluginFile.readManifest(ByteArrayInputStream(bytes)) as PluginManifestResult.Ok
        assertEquals(listOf(PluginPermission.Storage), read.permissions)
    }

    // ---- refusals ------------------------------------------------------

    @Test
    fun `an archive with no manifest is not a plugin`() {
        val bytes = rawArchive("main.py" to "x = 1".toByteArray())
        assertEquals(PluginImportResult.NotAPlugin, import(bytes, store()))
    }

    @Test
    fun `something that is not a zip is not a plugin`() {
        // ZipInputStream reports arbitrary bytes as an archive with no entries
        // rather than throwing, so this lands on "not a plugin" — which is the
        // truthful message anyway. Failed is reserved for "couldn't read or write".
        assertEquals(PluginImportResult.NotAPlugin, import("not a zip at all".toByteArray(), store()))
    }

    @Test
    fun `a manifest with no script is refused`() {
        val bytes = rawArchive(
            PluginFile.MANIFEST to PluginManifestCodec.encode(manifest()).toByteArray(),
        )
        val result = import(bytes, store()) as PluginImportResult.Rejected
        // Which line refused, rather than the English that line happens to be
        // worded in today. The arguments are what fill %1$s and %2$s, so this
        // still proves the message names the script file the manifest asked for.
        assertEquals(
            PluginText.Resource(R.string.core_plugins_reject_missing_script, "Demo", "main.py"),
            result.reasonText,
        )
    }

    @Test
    fun `an empty script is refused`() {
        val bytes = rawArchive(
            PluginFile.MANIFEST to PluginManifestCodec.encode(manifest()).toByteArray(),
            "main.py" to ByteArray(0),
        )
        assertTrue(import(bytes, store()) is PluginImportResult.Rejected)
    }

    @Test
    fun `a Lua plugin is refused with a reason that says so`() {
        // What a Lua plugin's manifest looked like: no language, an API level below 4.
        val luaManifest = rawManifest(apiVersion = 3, entry = "main.lua")
        val bytes = rawArchive(
            PluginFile.MANIFEST to luaManifest.toByteArray(),
            "main.lua" to "function render() return ui.label { text = 'x' } end".toByteArray(),
        )
        val result = import(bytes, store()) as PluginImportResult.Rejected
        // The resource id is the assertion; the plugin name fills %1$s.
        assertEquals(PluginText.Resource(R.string.core_plugins_reject_lua, "Demo"), result.reasonText)

        val saidSo = rawArchive(
            PluginFile.MANIFEST to rawManifest(apiVersion = 4, entry = "main.lua", language = "lua").toByteArray(),
            "main.lua" to "x".toByteArray(),
        )
        assertEquals(
            PluginImportResult.Rejected(PluginText.Resource(R.string.core_plugins_reject_lua, "Demo")),
            import(saidSo, store()),
        )
    }

    @Test
    fun `an API 4 manifest that names no language or entry is a Python plugin in main py`() {
        val store = store()
        val bytes = rawArchive(
            PluginFile.MANIFEST to rawManifest(apiVersion = 4, entry = null).toByteArray(),
            "main.py" to "def render():\n    return ui.label(\"x\")".toByteArray(),
        )
        val imported = import(bytes, store) as PluginImportResult.Imported
        assertTrue(imported.plugin.isPython)
        assertEquals("def render():\n    return ui.label(\"x\")", store.script("com.example.demo"))
    }

    @Test
    fun `a script that is not valid text is refused`() {
        val bytes = rawArchive(
            PluginFile.MANIFEST to PluginManifestCodec.encode(manifest()).toByteArray(),
            "main.py" to byteArrayOf(0x2D, 0x2D, 0xC3.toByte(), 0x28),
        )
        assertTrue(import(bytes, store()) is PluginImportResult.Rejected)
    }

    @Test
    fun `an oversized script is reported rather than silently truncated`() {
        val script = "# " + "x".repeat(PluginFile.MAX_SCRIPT_BYTES)
        val bytes = rawArchive(
            PluginFile.MANIFEST to PluginManifestCodec.encode(manifest()).toByteArray(),
            "main.py" to script.toByteArray(),
        )
        val result = import(bytes, store()) as PluginImportResult.Rejected
        // %1$s is the plugin name and %2$d the cap in KB, so the message still
        // tells the user how big is too big — checked as an argument now, which
        // survives the sentence being reworded or translated.
        assertEquals(
            PluginText.Resource(
                R.string.core_plugins_reject_script_too_large,
                "Demo",
                PluginFile.MAX_SCRIPT_BYTES / 1024,
            ),
            result.reasonText,
        )
    }

    @Test
    fun `an unknown permission is refused at import`() {
        val bytes = rawArchive(
            PluginFile.MANIFEST to
                PluginManifestCodec.encode(manifest(permissions = listOf("clipboard.read")))
                    .toByteArray(),
            "main.py" to "x = 1".toByteArray(),
        )
        assertTrue(import(bytes, store()) is PluginImportResult.Rejected)
    }

    // ---- hostile archives ----------------------------------------------

    @Test
    fun `entry names are never used as paths`() {
        val store = store()
        val escape = "../../../../../../tmp/wmkeyboard-plugin-escape.txt"
        val bytes = rawArchive(
            PluginFile.MANIFEST to
                PluginManifestCodec.encode(manifest(entry = escape)).toByteArray(),
            escape to "x = 1".toByteArray(),
        )
        // The entry resolves as a lookup key and the script lands in the plugin's
        // own directory; nothing is ever written to the name the archive chose.
        assertTrue(import(bytes, store) is PluginImportResult.Imported)
        assertEquals("x = 1", store.script("com.example.demo"))
        assertFalse(java.io.File("/tmp/wmkeyboard-plugin-escape.txt").exists())
    }

    @Test
    fun `an archive with too many entries stops being read`() {
        val entries = buildList {
            add(PluginFile.MANIFEST to PluginManifestCodec.encode(manifest()).toByteArray())
            repeat(200) { add("filler$it.txt" to "x".toByteArray()) }
            add("main.py" to "x = 1".toByteArray())
        }.toTypedArray()
        // The script sits past the entry cap, so it is never reached.
        assertTrue(import(rawArchive(*entries), store()) is PluginImportResult.Rejected)
    }

    @Test
    fun `a locked store cannot be written to`() {
        assertEquals(
            PluginImportResult.Failed,
            import(archive(manifest(), "x = 1"), PluginStore(null)),
        )
    }

    @Test
    fun `a plugin file is named after the plugin`() {
        assertEquals("Demo-1.0.0.wmplugin", PluginFile.fileName(manifest()))
        assertEquals("Demo.wmplugin", PluginFile.fileName(manifest().copy(pluginVersion = "")))
        assertEquals("Demo-2.wmplugin", PluginFile.fileName(manifest().copy(pluginVersion = "../2/")))
        assertNull(PluginPermission.parse("network:example.com"))
    }

    private companion object {
        var counter = 0
    }
}

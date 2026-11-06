package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestCodecTest {

    private fun manifest(
        id: String = "com.example.cipher",
        name: String = "Cipher Tool",
        version: String = "1.0.0",
        apiVersion: Int = 1,
        permissions: String = "[]",
        format: String = PluginManifestCodec.FORMAT,
        extra: String = "",
    ) = """
        {
          "format": "$format",
          "version": 1,
          "id": "$id",
          "name": "$name",
          "pluginVersion": "$version",
          "author": "Example",
          "description": "Caesar and Vigenere ciphers.",
          "apiVersion": $apiVersion,
          "entry": "main.lua",
          "permissions": $permissions$extra
        }
    """.trimIndent()

    // ---- happy path ----------------------------------------------------

    @Test
    fun `a well formed manifest is accepted`() {
        val read = PluginManifestCodec.read(manifest()) as PluginManifestResult.Ok
        assertEquals("com.example.cipher", read.manifest.id)
        assertEquals("Cipher Tool", read.manifest.name)
        assertEquals("1.0.0", read.manifest.pluginVersion)
        assertTrue(read.permissions.isEmpty())
    }

    @Test
    fun `a declared storage permission is parsed`() {
        val read = PluginManifestCodec.read(manifest(permissions = """["storage"]"""))
            as PluginManifestResult.Ok
        assertEquals(listOf(PluginPermission.Storage), read.permissions)
        assertEquals(listOf("storage"), read.manifest.permissions)
    }

    @Test
    fun `a duplicated permission is kept once`() {
        val read = PluginManifestCodec.read(manifest(permissions = """["storage","storage"]"""))
            as PluginManifestResult.Ok
        assertEquals(1, read.permissions.size)
    }

    @Test
    fun `unknown fields are ignored so newer manifests still read`() {
        val read = PluginManifestCodec.read(manifest(extra = ""","somethingNew": true"""))
        assertTrue(read is PluginManifestResult.Ok)
    }

    @Test
    fun `a missing apiVersion is treated as the first level`() {
        val text = """
            {"format":"${PluginManifestCodec.FORMAT}","id":"com.example.a",
             "name":"A","pluginVersion":"1.0.0"}
        """.trimIndent()
        val read = PluginManifestCodec.read(text) as PluginManifestResult.Ok
        assertEquals(1, read.manifest.apiVersion)
        assertEquals(PluginManifestCodec.DEFAULT_ENTRY, read.manifest.entry)
    }

    // ---- refusals ------------------------------------------------------

    @Test
    fun `a file that is not a plugin is not a plugin`() {
        assertEquals(PluginManifestResult.NotAPlugin, PluginManifestCodec.read("not json at all"))
        assertEquals(
            PluginManifestResult.NotAPlugin,
            PluginManifestCodec.read(manifest(format = "wmkeyboard-stickers")),
        )
    }

    @Test
    fun `an unknown permission refuses the whole manifest`() {
        val read = PluginManifestCodec.read(manifest(permissions = """["network:evil.com"]"""))
        val rejected = read as PluginManifestResult.Rejected
        // The permission the manifest asked for is echoed back to the user: it
        // fills %1$s, and the plugin's name fills %2$s. Asserting the resource id
        // and the arguments proves that without pinning the English sentence.
        assertEquals(
            PluginText.Resource(
                R.string.core_plugins_reject_unknown_permission,
                "network:evil.com",
                "Cipher Tool",
            ),
            rejected.reasonText,
        )
    }

    @Test
    fun `a newer api version is refused`() {
        val read = PluginManifestCodec.read(manifest(apiVersion = PluginManifestCodec.API_VERSION + 1))
        assertTrue(read is PluginManifestResult.Rejected)
    }

    @Test
    fun `the current api version is accepted with the reminders permission`() {
        val read = PluginManifestCodec.read(
            manifest(apiVersion = PluginManifestCodec.API_VERSION, permissions = """["storage", "reminders"]"""),
        ) as PluginManifestResult.Ok
        assertEquals(listOf(PluginPermission.Storage, PluginPermission.Reminders), read.permissions)
    }

    // ---- selection actions (API 3) -----------------------------------------

    private val oneAction = """,
          "actions": [{"id": "rot13", "name": "ROT13"}]"""

    @Test
    fun `actions with the selection permission are accepted`() {
        val read = PluginManifestCodec.read(
            manifest(apiVersion = 3, permissions = """["selection"]""", extra = oneAction),
        ) as PluginManifestResult.Ok
        assertEquals(listOf(PluginPermission.SelectedText), read.permissions)
        assertEquals("rot13", read.manifest.actions.single().id)
        assertTrue(read.manifest.actions.single().replaces)
        assertEquals(PluginActionSpec.INPUT_SELECTION, read.manifest.actions.single().input)
    }

    @Test
    fun `actions without the selection permission are refused`() {
        val read = PluginManifestCodec.read(manifest(apiVersion = 3, extra = oneAction))
        assertEquals(
            PluginText.of(R.string.core_plugins_reject_actions_need_selection, "Cipher Tool"),
            (read as PluginManifestResult.Rejected).reasonText,
        )
    }

    @Test
    fun `the selection permission without actions is refused`() {
        val read = PluginManifestCodec.read(manifest(apiVersion = 3, permissions = """["selection"]"""))
        assertEquals(
            PluginText.of(R.string.core_plugins_reject_selection_without_actions, "Cipher Tool"),
            (read as PluginManifestResult.Rejected).reasonText,
        )
    }

    @Test
    fun `repeated or malformed action ids are refused`() {
        for (actions in listOf(
            """[{"id": "a", "name": "A"}, {"id": "a", "name": "B"}]""",
            """[{"id": "Has Space", "name": "A"}]""",
            """[{"id": "", "name": "A"}]""",
        )) {
            val read = PluginManifestCodec.read(
                manifest(apiVersion = 3, permissions = """["selection"]""", extra = ",\n\"actions\": $actions"),
            )
            assertTrue(actions, read is PluginManifestResult.Rejected)
        }
    }

    @Test
    fun `an action asking for input this build cannot give is refused`() {
        val read = PluginManifestCodec.read(
            manifest(
                apiVersion = 3,
                permissions = """["selection"]""",
                extra = """, "actions": [{"id": "a", "name": "A", "input": "clipboard"}]""",
            ),
        )
        assertTrue(read is PluginManifestResult.Rejected)
    }

    @Test
    fun `a python plugin is actions only and asks for nothing else`() {
        val ok = PluginManifestCodec.read(
            manifest(
                apiVersion = 3,
                permissions = """["selection"]""",
                extra = oneAction + """, "language": "python", "panel": true""",
            ),
        ) as PluginManifestResult.Ok
        assertFalse("a python plugin never draws a panel", ok.manifest.panel)
        assertEquals(PluginManifestCodec.DEFAULT_PYTHON_ENTRY, ok.manifest.entry)

        val withStorage = PluginManifestCodec.read(
            manifest(
                apiVersion = 3,
                permissions = """["selection", "storage"]""",
                extra = oneAction + """, "language": "python"""",
            ),
        )
        assertTrue(withStorage is PluginManifestResult.Rejected)
    }

    @Test
    fun `an unknown language is refused`() {
        val read = PluginManifestCodec.read(manifest(apiVersion = 3, extra = """, "language": "ruby""""))
        assertTrue(read is PluginManifestResult.Rejected)
    }

    @Test
    fun `a plugin with no panel and no actions is refused`() {
        val read = PluginManifestCodec.read(manifest(apiVersion = 3, extra = """, "panel": false"""))
        assertTrue(read is PluginManifestResult.Rejected)
    }

    @Test
    fun `an id that could escape its directory is refused`() {
        for (bad in listOf("..", "../evil", "a/b", "a\\b", "ab", "", " ", "/etc/passwd")) {
            assertTrue(
                "expected $bad to be refused",
                PluginManifestCodec.read(manifest(id = bad)) is PluginManifestResult.Rejected,
            )
        }
    }

    @Test
    fun `an uppercase id is normalised so two ids cannot share a directory`() {
        val read = PluginManifestCodec.read(manifest(id = "COM.Example.Cipher"))
            as PluginManifestResult.Ok
        assertEquals("com.example.cipher", read.manifest.id)
    }

    @Test
    fun `a nameless plugin is refused`() {
        assertTrue(PluginManifestCodec.read(manifest(name = "   ")) is PluginManifestResult.Rejected)
    }

    @Test
    fun `a versionless plugin is refused`() {
        assertTrue(PluginManifestCodec.read(manifest(version = "")) is PluginManifestResult.Rejected)
    }

    // ---- display safety ------------------------------------------------

    @Test
    fun `a name cannot smuggle direction overrides above its permission list`() {
        // The manifest carries the JSON escape for RIGHT-TO-LEFT OVERRIDE, which
        // would let a name reorder the text drawn around it -- including the list
        // of what that plugin is allowed to do.
        val read = PluginManifestCodec.read(manifest(name = "Safe\\u202End"))
            as PluginManifestResult.Ok
        assertFalse(read.manifest.name.contains('\u202E'))
        assertEquals("Safend", read.manifest.name)
    }

    @Test
    fun `a name is capped so it cannot crowd out the rest of the dialog`() {
        val long = "x".repeat(500)
        val read = PluginManifestCodec.read(manifest(name = long)) as PluginManifestResult.Ok
        assertEquals(PluginManifestCodec.MAX_NAME, read.manifest.name.length)
    }

    @Test
    fun `emoji survive the display filter`() {
        // Surrogate halves report themselves as surrogates, so a per-char filter
        // would delete every emoji and non-BMP script from a plugin name.
        val name = "🔐 Cipher"
        val read = PluginManifestCodec.read(manifest(name = name)) as PluginManifestResult.Ok
        assertEquals(name, read.manifest.name)
    }

    @Test
    fun `newlines never reach a displayed field`() {
        val read = PluginManifestCodec.read(manifest(name = "Line\\none")) as PluginManifestResult.Ok
        assertEquals("Lineone", read.manifest.name)
    }
}

package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.core.plugins.PluginManifestCodec.Field
import com.wasimaster.wmkeyboard.plugins.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginManifestCodec.problems] is what the plugin editor's manifest form
 * validates with. The promise worth pinning is that it and [PluginManifestCodec.read]
 * are one set of rules, so a form that says "valid" never meets an importer that says no.
 */
class PluginManifestFormTest {

    private fun manifest(
        id: String = "com.example.demo",
        name: String = "Demo",
        version: String = "1.0.0",
        author: String = "Example",
        description: String = "A demo.",
        apiVersion: Int = PluginManifestCodec.API_VERSION,
        entry: String = "main.py",
        permissions: List<String> = emptyList(),
        language: String = PluginManifestCodec.LANGUAGE_PYTHON,
        panel: Boolean = true,
        actions: List<PluginActionSpec> = emptyList(),
    ) = PluginManifest(
        format = PluginManifestCodec.FORMAT,
        version = PluginManifestCodec.VERSION,
        id = id,
        name = name,
        pluginVersion = version,
        author = author,
        description = description,
        apiVersion = apiVersion,
        entry = entry,
        permissions = permissions,
        language = language,
        panel = panel,
        actions = actions,
    )

    private fun readBack(manifest: PluginManifest) = PluginManifestCodec.read(PluginManifestCodec.encode(manifest))

    private fun fields(manifest: PluginManifest) = PluginManifestCodec.problems(manifest).map { it.field }

    @Test
    fun `problems are empty exactly when read accepts`() {
        val cases = listOf(
            manifest(),
            manifest(id = "ab"),
            manifest(id = "  Com.Example.Upper  "),
            manifest(id = "../escape"),
            manifest(id = ""),
            manifest(id = "no spaces allowed"),
            manifest(name = ""),
            manifest(name = "\u202Etxt.exe"),
            manifest(name = "\u200B\u200B"),
            manifest(version = ""),
            manifest(version = "   "),
            manifest(apiVersion = 0),
            manifest(apiVersion = -3),
            manifest(apiVersion = 2),
            manifest(apiVersion = 3),
            manifest(permissions = listOf("storage", "reminders")),
            manifest(permissions = listOf("storage")),
            manifest(permissions = listOf("storage", "storage")),
            manifest(permissions = listOf(" storage ")),
            manifest(permissions = listOf("camera")),
            manifest(author = "a".repeat(500), description = "d".repeat(900)),
            manifest(entry = ""),
            manifest(name = "", version = "", id = "!!", apiVersion = 9),
            manifest(language = PluginManifestCodec.LANGUAGE_LUA),
            manifest(language = PluginManifestCodec.LANGUAGE_LUA, apiVersion = 2),
            manifest(language = "ruby"),
            manifest(language = " python "),
            manifest(panel = false),
            manifest(panel = false, permissions = listOf("selection"), actions = listOf(PluginActionSpec(id = "a", name = "A"))),
            manifest(permissions = listOf("selection")),
            manifest(actions = listOf(PluginActionSpec(id = "a", name = "A"))),
        )
        for (case in cases) {
            val accepted = readBack(case) is PluginManifestResult.Ok
            assertEquals("for $case", accepted, PluginManifestCodec.problems(case).isEmpty())
        }
    }

    @Test
    fun `read refuses with the first problem`() {
        val broken = manifest(name = "", version = "", id = "!!")
        val problems = PluginManifestCodec.problems(broken)
        assertEquals(listOf(Field.ID, Field.NAME, Field.VERSION), problems.map { it.field })

        val rejected = readBack(broken) as PluginManifestResult.Rejected
        assertEquals(problems.first().text, rejected.reasonText)
    }

    @Test
    fun `each problem names the field it belongs to`() {
        assertEquals(listOf(Field.API_VERSION), fields(manifest(apiVersion = PluginManifestCodec.API_VERSION + 1)))
        assertEquals(listOf(Field.ID), fields(manifest(id = "no spaces allowed")))
        assertEquals(listOf(Field.NAME), fields(manifest(name = " ")))
        assertEquals(listOf(Field.VERSION), fields(manifest(version = "")))
        assertEquals(listOf(Field.PERMISSIONS), fields(manifest(permissions = listOf("storage", "telepathy"))))
        assertEquals(listOf(Field.API_VERSION), fields(manifest(language = PluginManifestCodec.LANGUAGE_LUA)))
        assertEquals(listOf(Field.ENTRY), fields(manifest(language = "ruby")))
        assertEquals(listOf(Field.ACTIONS), fields(manifest(panel = false)))
    }

    @Test
    fun `the language and panel rules say why in the importer's words`() {
        fun only(manifest: PluginManifest) = PluginManifestCodec.problems(manifest).single().text
        assertEquals(PluginText.of(R.string.core_plugins_reject_lua, "Demo"), only(manifest(language = "lua")))
        assertEquals(PluginText.of(R.string.core_plugins_reject_unknown_language, "Demo"), only(manifest(language = "ruby")))
        assertEquals(PluginText.of(R.string.core_plugins_reject_nothing_to_show, "Demo"), only(manifest(panel = false)))
    }

    @Test
    fun `a python manifest written at any level reads back as python`() {
        for (level in 1..PluginManifestCodec.API_VERSION) {
            val read = readBack(manifest(apiVersion = level)) as PluginManifestResult.Ok
            assertEquals(PluginManifestCodec.LANGUAGE_PYTHON, read.manifest.language)
        }
    }

    @Test
    fun `an unknown permission is echoed with the plugin name`() {
        val problem = PluginManifestCodec.problems(manifest(permissions = listOf("telepathy"))).single()
        assertEquals(
            PluginText.of(R.string.core_plugins_reject_unknown_permission, "telepathy", "Demo"),
            problem.text,
        )
    }

    @Test
    fun `author, description and entry are shortened, never refused`() {
        val long = manifest(author = "a".repeat(1_000), description = "d".repeat(1_000), entry = "e".repeat(200))
        assertTrue(PluginManifestCodec.problems(long).isEmpty())
    }

    @Test
    fun `sanitise shows exactly what read stores`() {
        val raw = manifest(
            id = "  Com.Example.Demo ",
            name = "  Hi\u200D\u202E\u0007there  ",
            version = " 1.0 ",
            author = "a".repeat(100),
            description = "d".repeat(400),
            entry = "",
        )
        val stored = (readBack(raw) as PluginManifestResult.Ok).manifest

        assertEquals(stored.id, PluginManifestCodec.sanitise(raw.id, Field.ID))
        assertEquals(stored.name, PluginManifestCodec.sanitise(raw.name, Field.NAME))
        assertEquals(stored.pluginVersion, PluginManifestCodec.sanitise(raw.pluginVersion, Field.VERSION))
        assertEquals(stored.author, PluginManifestCodec.sanitise(raw.author, Field.AUTHOR))
        assertEquals(stored.description, PluginManifestCodec.sanitise(raw.description, Field.DESCRIPTION))
        assertEquals(stored.entry, PluginManifestCodec.sanitise(raw.entry, Field.ENTRY))

        assertEquals("com.example.demo", stored.id)
        assertEquals("Hithere", stored.name)
        assertEquals("1.0", stored.pluginVersion)
        assertEquals("main.py", stored.entry)
    }

    @Test
    fun `sanitise keeps whole surrogate pairs and drops lone ones`() {
        assertEquals("a\uD83D\uDE00b", PluginManifestCodec.sanitise("a\uD83D\uDE00\uD800b", Field.NAME))
    }

    @Test
    fun `sanitise cuts each displayed field at its cap`() {
        assertEquals(PluginManifestCodec.MAX_NAME, PluginManifestCodec.sanitise("n".repeat(100), Field.NAME).length)
        assertEquals(32, PluginManifestCodec.sanitise("v".repeat(100), Field.VERSION).length)
        assertEquals(64, PluginManifestCodec.sanitise("a".repeat(100), Field.AUTHOR).length)
        assertEquals(280, PluginManifestCodec.sanitise("d".repeat(400), Field.DESCRIPTION).length)
        assertEquals(64, PluginManifestCodec.sanitise("e".repeat(100), Field.ENTRY).length)
    }
}

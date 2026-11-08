package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.plugins.PluginManifest
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestCodec
import com.wasimaster.wmkeyboard.core.plugins.PluginPermission
import com.wasimaster.wmkeyboard.core.plugins.PyHostShape
import com.wasimaster.wmkeyboard.core.plugins.PySeverity
import com.wasimaster.wmkeyboard.core.plugins.PythonTooling
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The templates a new plugin starts from, held to the demo plugins they copy. */
class PluginTemplatesTest {

    private val directory: File = listOf(
        File("src/main/assets/${PluginTemplate.ASSET_DIR}"),
        File("app/src/main/assets/${PluginTemplate.ASSET_DIR}"),
    ).first { it.isDirectory }

    private fun script(template: PluginTemplate): String =
        template.asset?.let { File(directory, it).readText() } ?: BLANK_PLUGIN_SCRIPT

    private fun demo(file: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("plugins/$file")) { "no demo $file" }
            .bufferedReader()
            .use { it.readText() }

    /** The manifest a draft from [template] starts with, as [newDraftFromTemplate] writes it. */
    private fun manifest(template: PluginTemplate) = PluginManifest(
        format = PluginManifestCodec.FORMAT,
        id = "my.${template.name.lowercase().replace('_', '-')}",
        name = "Template",
        pluginVersion = "0.1.0",
        permissions = template.permissions.map { it.wire },
        actions = template.actions,
    )

    @Test
    fun `every template file is its demo plugin, character for character`() {
        for (template in PluginTemplate.entries) {
            val file = template.asset ?: continue
            assertTrue(file, file.endsWith(".py"))
            assertEquals(file, demo(file), script(template))
        }
    }

    @Test
    fun `every file in the templates folder is listed`() {
        assertEquals(PluginTemplate.entries.mapNotNull { it.asset }.toSet(), directory.list().orEmpty().toSet())
    }

    @Test
    fun `a template declares each permission exactly when it uses it`() {
        for (template in PluginTemplate.entries) {
            val script = script(template)
            assertEquals(template.name, "wm.storage" in script, PluginPermission.Storage in template.permissions)
            assertEquals(template.name, "wm.reminders" in script, PluginPermission.Reminders in template.permissions)
            assertEquals(template.name, "def transform(" in script, PluginPermission.SelectedText in template.permissions)
            assertEquals(template.name, "def transform(" in script, template.actions.isNotEmpty())
        }
    }

    @Test
    fun `a draft from any template has a manifest the importer accepts`() {
        for (template in PluginTemplate.entries) {
            assertEquals(template.name, emptyList<PluginManifestCodec.Problem>(), PluginManifestCodec.problems(manifest(template)))
        }
    }

    @Test
    fun `every template runs as far as the interpreter's parser goes`() {
        for (template in PluginTemplate.entries) {
            assertTrue(template.name, PythonCode.parses(script(template)))
        }
    }

    @Test
    fun `no template draws an error or a warning from the checks`() {
        val problems = PluginTemplate.entries.flatMap { template ->
            val host = PyHostShape.of(manifest(template))
            PythonTooling.analyse(script(template), host).diagnostics
                .filter { it.severity != PySeverity.INFO }
                .map { "${template.name} ${it.code} ${it.args}" }
        }
        assertEquals(emptyList<String>(), problems)
    }
}

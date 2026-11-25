package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The manifest's `previews` and `syntax`, the `previews` permission, and how the store keeps them. */
class PluginPreviewManifestTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val office = PluginPreviewSpec(id = "office", name = "Office", extensions = listOf("docx", "xlsx"), mimes = listOf("application/vnd.openxmlformats-officedocument.*"), thumbnails = true)

    private fun manifest(
        permissions: List<String> = listOf("previews"),
        previews: List<PluginPreviewSpec> = listOf(office),
        syntax: List<PluginSyntaxSpec> = emptyList(),
        apiVersion: Int = 5,
    ) = PluginManifest(
        format = PluginManifestCodec.FORMAT,
        id = "com.example.office",
        name = "Office",
        pluginVersion = "1.0",
        apiVersion = apiVersion,
        permissions = permissions,
        panel = false,
        previews = previews,
        syntax = syntax,
    )

    private fun problems(m: PluginManifest) = PluginManifestCodec.problems(m).map { (it.text as PluginText.Resource).textRes }

    private fun grammar(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `a preview plugin without a panel is accepted`() {
        assertEquals(emptyList<Int>(), problems(manifest()))
        val read = PluginManifestCodec.read(PluginManifestCodec.encode(manifest())) as PluginManifestResult.Ok
        assertEquals(listOf("docx", "xlsx"), read.manifest.previews.single().extensions)
        assertTrue(PluginPermission.Previews in read.permissions)
    }

    @Test
    fun `previews need the permission, and the permission needs previews`() {
        assertTrue(R.string.core_plugins_reject_previews_need_permission in problems(manifest(permissions = emptyList())))
        assertTrue(R.string.core_plugins_reject_previews_permission_unused in problems(manifest(previews = emptyList(), syntax = listOf(toml()))))
    }

    @Test
    fun `a preview needs an id and a file type, and well formed ones`() {
        assertTrue(R.string.core_plugins_reject_bad_preview in problems(manifest(previews = listOf(PluginPreviewSpec(id = "x")))))
        assertTrue(R.string.core_plugins_reject_bad_preview in problems(manifest(previews = listOf(office.copy(id = "Office!")))))
        assertTrue(R.string.core_plugins_reject_bad_preview in problems(manifest(previews = listOf(office.copy(mimes = listOf("not a type"))))))
        assertTrue(R.string.core_plugins_reject_bad_preview in problems(manifest(previews = listOf(office, office))))
        assertEquals(emptyList<Int>(), problems(manifest(previews = listOf(PluginPreviewSpec(id = "docker", names = listOf("Dockerfile"))))))
    }

    @Test
    fun `previews need API 5`() {
        assertTrue(R.string.core_plugins_reject_permission_needs_api_5 in problems(manifest(apiVersion = 4)))
    }

    @Test
    fun `extensions are read without dots and in lower case, and max bytes is capped`() {
        val raw = manifest(previews = listOf(office.copy(extensions = listOf(".DOCX", "xlsx"), maxBytes = 1L shl 40)))
        val read = PluginManifestCodec.read(PluginManifestCodec.encode(raw)) as PluginManifestResult.Ok
        val spec = read.manifest.previews.single()
        assertEquals(listOf("docx", "xlsx"), spec.extensions)
        assertEquals(PluginPreviewSpec.MAX_MAX_BYTES, spec.maxBytes)
    }

    @Test
    fun `a preview matches by name, extension and type family, closest first`() {
        assertTrue(office.matches("Report.DOCX", null))
        assertTrue(office.matches("noext", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        assertFalse(office.matches("a.pdf", "application/pdf"))
        assertFalse(office.matches("a.bin", "application/octet-stream"))
        val docker = PluginPreviewSpec(id = "d", names = listOf("Dockerfile"))
        assertTrue(docker.fit("dockerfile", null) > office.fit("a.docx", null))
        assertTrue(office.fit("a.docx", null) > office.fit("x", "application/vnd.openxmlformats-officedocument.x"))
        assertEquals(0, office.fit("a.txt", "text/plain"))
    }

    private fun toml(g: String = """{"lineComment": "#", "strings": ["\""], "rules": [{"match": "^\\[[^\\]]*\\]", "kind": "heading", "flags": "m"}]}""") =
        PluginSyntaxSpec(id = "toml", name = "TOML", extensions = listOf("toml"), grammar = grammar(g))

    @Test
    fun `syntax needs no permission, and a grammar that does not compile is refused`() {
        val syntaxOnly = manifest(permissions = emptyList(), previews = emptyList(), syntax = listOf(toml()))
        assertEquals(emptyList<Int>(), problems(syntaxOnly))
        val broken = toml("""{"rules": [{"match": "([a-z", "kind": "keyword"}]}""")
        assertTrue(R.string.core_plugins_reject_bad_syntax in problems(syntaxOnly.copy(syntax = listOf(broken))))
        val unknownKind = toml("""{"rules": [{"match": "x", "kind": "sparkle"}]}""")
        assertTrue(R.string.core_plugins_reject_bad_syntax in problems(syntaxOnly.copy(syntax = listOf(unknownKind))))
    }

    @Test
    fun `a grammar in assets is read in at install`() {
        val store = PluginStore(temp.newFolder())
        store.setSubsystemEnabled(true)
        val asset = PluginSyntaxSpec(id = "toml", extensions = listOf("toml"), grammar = JsonPrimitive("assets/toml.json"))
        val m = manifest(permissions = emptyList(), previews = emptyList(), syntax = listOf(asset))
        assertEquals(emptyList<Int>(), problems(m))
        store.adopt(m, "x = 1", assets = mapOf("toml.json" to """{"lineComment": "#"}""".toByteArray()))
        val kept = store.syntaxes().single()
        assertTrue(kept.syntax.grammar is JsonObject)
        val tokens = PluginSyntax.tokens("a = 1 # note", PluginSyntax.grammar(kept)!!)
        assertEquals(PluginSyntax.Kind.COMMENT, tokens.last().kind)
    }

    @Test
    fun `the store offers previews only while the subsystem and the plugin are on`() {
        val store = PluginStore(temp.newFolder())
        store.adopt(manifest(), "x = 1")
        assertTrue(store.previews().isEmpty())
        store.setSubsystemEnabled(true)
        val ref = store.previews().single()
        assertEquals("plugin:com.example.office/office", ref.key)
        assertEquals(ref, store.runnablePreview(ref.key))
        store.setEnabled("com.example.office", false)
        assertNull(store.runnablePreview(ref.key))
    }
}

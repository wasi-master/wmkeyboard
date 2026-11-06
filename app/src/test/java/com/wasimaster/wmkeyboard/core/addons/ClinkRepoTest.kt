package com.wasimaster.wmkeyboard.core.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Clink repository read as an addon repository. The manifests are cut down
 * from the ones `anti-ltd/clink-*` publish, with made-up hashes.
 */
class ClinkRepoTest {

    private val location = ClinkRepo.Location("someone", "clink-things")
    private val sha = "a".repeat(64)
    private val otherSha = "b".repeat(64)

    private fun asset(path: String, hash: String = sha) =
        """{"path": "$path", "url": "https://github.com/someone/clink-things/releases/download/v1/$path",
            "sha256": "$hash", "byteCount": 120}"""

    @Test
    fun `a GitHub manifest URL locates the Clink catalogue`() {
        val found = ClinkRepo.locate("https://raw.githubusercontent.com/anti-ltd/clink-themes/HEAD/wmkeyboard-repo.json")
        assertEquals(ClinkRepo.Location("anti-ltd", "clink-themes", "HEAD"), found)
        assertEquals(
            "https://github.com/anti-ltd/clink-themes/releases/latest/download/manifest.json",
            found!!.releaseManifestUrl,
        )
        assertNull(ClinkRepo.locate("https://example.com/addons/wmkeyboard-repo.json"))
        assertNull(ClinkRepo.locate("https://raw.githubusercontent.com/a/b/HEAD/sub/wmkeyboard-repo.json"))
    }

    @Test
    fun `owner slash repository resolves to GitHub, as Clink spells it`() {
        assertEquals(
            "https://raw.githubusercontent.com/anti-ltd/clink-themes/HEAD/wmkeyboard-repo.json",
            AddonRepoCodec.resolveManifestUrl("anti-ltd/clink-themes"),
        )
        // A host has a dot in it, so this is still a directory on that host.
        assertEquals(
            "https://example.com/addons/wmkeyboard-repo.json",
            AddonRepoCodec.resolveManifestUrl("example.com/addons"),
        )
    }

    @Test
    fun `themes become theme entries with their previews`() {
        val manifest = """{"version": "themes-x", "themes": [
            {"id": "brick", "name": "Brick", "version": "themes-x", "platform": "both",
             "preview": {"id": "brick", "material": "3d", "isDark": false}, "asset": ${asset("brick.clinktheme")}},
            {"id": "nebula", "name": "Nebula", "version": "themes-x",
             "preview": {"id": "repo-nebula", "isDark": true}, "asset": ${asset("nebula.clinktheme", otherSha)}},
            {"id": "phone-only", "name": "iPhone", "platform": "ios", "asset": ${asset("p.clinktheme")}}
        ]}"""
        val repo = ClinkRepo.convert(manifest, location, previewIds = setOf("repo-nebula"))!!
        assertEquals("clink.someone.clink-things", repo.repo.id)
        assertEquals(listOf("brick", "nebula"), repo.addons.map { it.id })
        val brick = repo.addons.first()
        assertEquals(AddonType.Theme, brick.type)
        assertEquals(sha, brick.sha256)
        assertEquals(120L, brick.sizeBytes)
        assertEquals(listOf("3d", "light"), brick.tags)
        assertTrue(brick.previews.isEmpty())
        // The picture is named by the id inside the file.
        assertEquals(
            listOf("https://raw.githubusercontent.com/someone/clink-things/HEAD/README-assets/previews/repo-nebula.png"),
            repo.addons[1].previews,
        )
    }

    @Test
    fun `the converted manifest round-trips through the codec`() {
        val manifest = """{"version": "fonts-x", "fonts": [{"id": "basic", "name": "Basic", "license": "OFL-1.1",
            "attribution": "Sorkin Type", "asset": ${asset("Basic-Regular.ttf")}}]}"""
        val encoded = ClinkRepo.encode(ClinkRepo.convert(manifest, location)!!)
        val decoded = AddonRepoCodec.decode(encoded)
        assertNotNull(decoded)
        val font = decoded!!.addons.single()
        assertEquals(AddonType.Font, font.type)
        assertEquals("OFL-1.1", font.license)
        assertEquals("Sorkin Type", font.description)
        assertTrue(font.path.startsWith("https://github.com/"))
    }

    @Test
    fun `a language pack offers its word list and emoji names`() {
        val manifest = """{"version": "v1", "packs": [
            {"code": "hi_latn", "version": "v1", "assets": [${asset("hi_latn.clex")}, ${asset("hi_latn.cngm")}]},
            {"code": "af", "version": "v1", "assets": [${asset("af.clex")}, ${asset("af.emoji.json")},
              ${asset("af.mlmodelc/model.mil")}]}
        ]}"""
        val addons = ClinkRepo.convert(manifest, location)!!.addons
        assertEquals(listOf("hi_latn-words", "af-words", "af-emoji"), addons.map { it.id })
        assertEquals("hi_rom", addons[0].langId)
        assertEquals(AddonType.Dictionary, addons[0].type)
        assertEquals("Hindi (Latin)", addons[0].name)
        assertEquals(AddonType.EmojiKeywords, addons[2].type)
        assertEquals("af", addons[2].langId)
    }

    @Test
    fun `regional variants fold into the language`() {
        assertEquals("pt", ClinkRepo.languageId("pt_br"))
        assertEquals("ur_rom", ClinkRepo.languageId("ur_latn"))
        assertEquals("pap", ClinkRepo.languageId("pap"))
    }

    @Test
    fun `code kinds are not offered and the description says so`() {
        val manifest = """{"version": "panels-x", "panels": [{"id": "kaomoji", "name": "Kaomoji",
            "asset": ${asset("kaomoji.clinkpanel")}}]}"""
        val repo = ClinkRepo.convert(manifest, location)!!
        assertTrue(repo.addons.isEmpty())
        assertTrue(repo.repo.description.contains("panels"))
    }

    @Test
    fun `actions become plugin entries carrying their checksum`() {
        val manifest = """{"version": "actions-x", "actions": [
            {"id": "reverse", "name": "Reverse", "icon": "arrow.left.arrow.right", "summary": "Flip the text backwards",
             "asset": ${asset("reverse.clinkext")}, "platform": "both"}]}"""
        val repo = ClinkRepo.convert(manifest, location)!!
        val entry = repo.addons.single()
        assertEquals(AddonType.Plugin, entry.type)
        assertEquals("clink.reverse", entry.id)
        assertEquals("Reverse", entry.name)
        assertEquals("Flip the text backwards", entry.description)
        // A plugin without a checksum is refused at download, so this one must carry it.
        assertEquals(sha, entry.sha256)
        assertFalse(repo.repo.description.contains("actions"))
    }

    @Test
    fun `entries are versioned by their own file hash`() {
        val manifest = """{"version": "sounds-x", "sounds": [{"id": "arcade", "name": "Arcade",
            "version": "sounds-x", "asset": ${asset("arcade.clinkpack")}}]}"""
        val entry = ClinkRepo.convert(manifest, location)!!.addons.single()
        assertEquals(AddonType.SoundPack, entry.type)
        assertEquals("#" + "a".repeat(12), entry.version)
        assertTrue(Semver.isNewer("#" + "b".repeat(12), entry.version))
        assertFalse(Semver.isNewer(entry.version, entry.version))
    }

    @Test
    fun `our own manifest is not a Clink one`() {
        assertFalse(ClinkRepo.looksLikeManifest("""{"format": "wmkeyboard-repo", "repo": {"id": "x"}, "addons": []}"""))
        assertNull(ClinkRepo.convert("""{"addons": []}""", location))
    }
}

package com.wasimaster.wmkeyboard.core.addons

import com.wasimaster.wmkeyboard.addons.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec is the addon layer's trust boundary: everything it takes in came
 * from a text field or a deep link, and every URL it hands back gets fetched.
 * These pin both halves — what counts as a repository, and what counts as a
 * URL worth fetching.
 */
class AddonRepoCodecTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("addons/$name")) {
            "missing test fixture addons/$name"
        }.use { it.readBytes().decodeToString() }

    // ---- manifest ------------------------------------------------------

    @Test
    fun `decodes the sample repository`() {
        val manifest = AddonRepoCodec.decode(fixture("wmkeyboard-repo.json"))
        assertNotNull(manifest)
        manifest!!
        assertEquals("com.wasimaster.wmkeyboard.sample", manifest.repo.id)
        assertTrue("sample should list addons", manifest.addons.isNotEmpty())
        // One of each real type is the point of the sample repo.
        val types = manifest.addons.map { it.type }.toSet()
        for (type in AddonType.entries - AddonType.Unknown) {
            assertTrue("sample repo has no $type", type in types)
        }
    }

    @Test
    fun `the sample repository's espanso entry declares the payload it ships`() {
        // The manifest states a hash and a size for every payload, and the app
        // verifies both before installing. A fixture whose numbers have drifted
        // from its file would pass every other test here and fail on a device.
        val entry = AddonRepoCodec.decode(fixture("wmkeyboard-repo.json"))!!
            .addons.single { it.type == AddonType.Espanso }
        val bytes = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("addons/${entry.path.substringAfterLast('/')}"),
        ) { "missing payload for ${entry.id}" }.use { it.readBytes() }
        assertEquals(entry.sizeBytes, bytes.size.toLong())
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        assertEquals(entry.sha256, digest.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `the sample repository states a licence for every addon`() {
        // Not a format requirement, but an addon whose licence nobody can find
        // is one nobody can safely reuse — and the sample is what people copy.
        val manifest = AddonRepoCodec.decode(fixture("wmkeyboard-repo.json"))!!
        val unlicensed = manifest.addons.filterNot { it.hasLicense }.map { it.id }
        assertEquals(emptyList<String>(), unlicensed)
    }

    @Test
    fun `the sample repository no longer ships a layout the app already has`() {
        // asset_fr_bepo ships with the app, so installing the repository's copy
        // left the user looking at "Français BÉPO" twice.
        val manifest = AddonRepoCodec.decode(fixture("wmkeyboard-repo.json"))!!
        assertNull(manifest.addons.firstOrNull { it.id == "fr-bepo" })
    }

    // ---- licences & languages -------------------------------------------

    @Test
    fun `licence fields decode and hasLicense follows any of them`() {
        val text = """
            {"format":"wmkeyboard-repo","version":1,
             "repo":{"id":"x","name":"X"},
             "addons":[
               {"id":"a","type":"font","name":"A","version":"1.0.0","path":"a.ttf",
                "license":"OFL-1.1","licenseFile":"fonts/OFL.txt"},
               {"id":"b","type":"font","name":"B","version":"1.0.0","path":"b.ttf",
                "licenseText":"Do what you like."},
               {"id":"c","type":"font","name":"C","version":"1.0.0","path":"c.ttf"}
             ]}
        """.trimIndent()
        val addons = AddonRepoCodec.decode(text)!!.addons.associateBy { it.id }
        assertEquals("OFL-1.1", addons.getValue("a").license)
        assertEquals("fonts/OFL.txt", addons.getValue("a").licenseFile)
        assertTrue(addons.getValue("a").hasLicense)
        assertTrue("inline text alone counts", addons.getValue("b").hasLicense)
        assertTrue("nothing stated", !addons.getValue("c").hasLicense)
    }

    @Test
    fun `languages merges langId and langIds without duplicates`() {
        val text = """
            {"format":"wmkeyboard-repo","version":1,
             "repo":{"id":"x","name":"X"},
             "addons":[
               {"id":"a","type":"font","name":"A","version":"1.0.0","path":"a.ttf",
                "langIds":["en"," ru ","el","en"]},
               {"id":"b","type":"dictionary","name":"B","version":"1.0.0","path":"b.txt",
                "langId":"fr","langIds":["fr","de"]},
               {"id":"c","type":"font","name":"C","version":"1.0.0","path":"c.ttf"}
             ]}
        """.trimIndent()
        val addons = AddonRepoCodec.decode(text)!!.addons.associateBy { it.id }
        assertEquals(listOf("en", "ru", "el"), addons.getValue("a").languages)
        assertEquals(listOf("fr", "de"), addons.getValue("b").languages)
        // No claim; the picker offers it everywhere.
        assertEquals(emptyList<String>(), addons.getValue("c").languages)
    }

    @Test
    fun `an emoji font is its own type, not a font`() {
        val text = """
            {"format":"wmkeyboard-repo","version":1,
             "repo":{"id":"x","name":"X"},
             "addons":[{"id":"twemoji","type":"emoji_font","name":"Twemoji",
                        "version":"1.0.0","path":"fonts/twemoji.ttf"}]}
        """.trimIndent()
        val entry = AddonRepoCodec.decode(text)!!.addons.single()
        assertEquals(AddonType.EmojiFont, entry.type)
        // Named as its own thing, not as a font: checked by the resource the
        // type points at rather than by the English in it, so rewording the
        // label cannot break this and swapping it for the font one still does.
        assertEquals(
            R.string.core_addons_type_emoji_font_singular_label,
            entry.type.singularLabelRes,
        )
    }

    @Test
    fun `previews are offered where the payload is the decision`() {
        val previewable = AddonType.entries.filter { it.previewable }.toSet()
        assertEquals(
            setOf(
                // Content is the choice: the words, the images, the sound itself.
                AddonType.Snippets,
                // And doubly so for an Espanso pack, whose preview also says
                // what converting it from that format costs.
                AddonType.Espanso,
                AddonType.Dictionary,
                // A keyword pack is judged the same way: a few emoji beside
                // the words this language calls them by is the whole question.
                AddonType.EmojiKeywords,
                AddonType.Sound,
                // A pack's variants, each playable on its own: how much it
                // varies is the question a pack raises, and one Play button
                // cannot answer it.
                AddonType.SoundPack,
                AddonType.Stickers,
                // A dynamic sticker pack is judged by which messages it answers:
                // its templates by name, with the phrases each is offered for.
                AddonType.DynamicStickers,
                // A plugin previews for a different reason -- not "is this any
                // good" but "should I let this run at all", which is why the
                // preview shows its capabilities rather than its content.
                AddonType.Plugin,
                // A vocabulary pack is a word list: a few cards from it say
                // what the whole study list is like better than its name does.
                AddonType.Vocabulary,
            ),
            previewable,
        )
    }

    @Test
    fun `a plugin is the only executable addon type`() {
        assertEquals(
            setOf(AddonType.Plugin),
            AddonType.entries.filter { it.isExecutable }.toSet(),
        )
    }

    @Test
    fun `rejects a manifest without the format tag`() {
        assertNull(AddonRepoCodec.decode("""{"version":1,"repo":{"id":"x","name":"X"}}"""))
    }

    @Test
    fun `rejects a manifest whose format tag is something else`() {
        val text = """{"format":"wmkeyboard-layout","version":1,"repo":{"id":"x","name":"X"}}"""
        assertNull(AddonRepoCodec.decode(text))
    }

    @Test
    fun `rejects junk rather than throwing`() {
        assertNull(AddonRepoCodec.decode("not json at all"))
        assertNull(AddonRepoCodec.decode(""))
    }

    @Test
    fun `an unknown addon type is dropped, not fatal`() {
        // Forward compatibility: a repository listing a type added after this
        // build shipped must still be usable for everything else it offers.
        val text = """
            {"format":"wmkeyboard-repo","version":1,
             "repo":{"id":"x","name":"X"},
             "addons":[
               {"id":"a","type":"theme","name":"A","version":"1.0.0","path":"a.wmtheme.json"},
               {"id":"b","type":"hologram","name":"B","version":"1.0.0","path":"b.holo"}
             ]}
        """.trimIndent()
        val manifest = AddonRepoCodec.decode(text)
        assertNotNull(manifest)
        assertEquals(listOf("a"), manifest!!.addons.map { it.id })
    }

    @Test
    fun `entries missing an id or path are dropped`() {
        val text = """
            {"format":"wmkeyboard-repo","version":1,
             "repo":{"id":"x","name":"X"},
             "addons":[
               {"id":"","type":"theme","name":"A","version":"1.0.0","path":"a.wmtheme.json"},
               {"id":"b","type":"theme","name":"B","version":"1.0.0","path":""},
               {"id":"c","type":"theme","name":"C","version":"1.0.0","path":"c.wmtheme.json"}
             ]}
        """.trimIndent()
        assertEquals(listOf("c"), AddonRepoCodec.decode(text)!!.addons.map { it.id })
    }

    @Test
    fun `unknown fields are ignored`() {
        val text = """
            {"format":"wmkeyboard-repo","version":1,"futureField":42,
             "repo":{"id":"x","name":"X","somethingNew":true},
             "addons":[{"id":"a","type":"theme","name":"A","version":"1.0.0",
                        "path":"a.wmtheme.json","notYetInvented":["x"]}]}
        """.trimIndent()
        assertEquals(1, AddonRepoCodec.decode(text)!!.addons.size)
    }

    // ---- manifest URL resolution ---------------------------------------

    @Test
    fun `github repository URL resolves to the raw manifest on HEAD`() {
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/HEAD/wmkeyboard-repo.json",
            AddonRepoCodec.resolveManifestUrl("https://github.com/user/repo"),
        )
    }

    @Test
    fun `a github tree URL resolves on its branch`() {
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/dev/wmkeyboard-repo.json",
            AddonRepoCodec.resolveManifestUrl("https://github.com/user/repo/tree/dev"),
        )
    }

    @Test
    fun `a direct raw manifest URL is used as-is`() {
        val url = "https://raw.githubusercontent.com/user/repo/HEAD/wmkeyboard-repo.json"
        assertEquals(url, AddonRepoCodec.resolveManifestUrl(url))
    }

    @Test
    fun `any other https directory gets the manifest name appended`() {
        assertEquals(
            "https://example.com/addons/wmkeyboard-repo.json",
            AddonRepoCodec.resolveManifestUrl("https://example.com/addons"),
        )
    }

    @Test
    fun `a scheme-less address is assumed to be https`() {
        // People paste addresses without typing the scheme.
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/HEAD/wmkeyboard-repo.json",
            AddonRepoCodec.resolveManifestUrl("github.com/user/repo"),
        )
    }

    @Test
    fun `trailing slashes and dot-git are tolerated`() {
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/HEAD/wmkeyboard-repo.json",
            AddonRepoCodec.resolveManifestUrl("https://github.com/user/repo.git/"),
        )
    }

    @Test
    fun `plain http is rejected rather than upgraded`() {
        // Silently upgrading would hide that the link asked for something the
        // app refuses to do.
        assertNull(AddonRepoCodec.resolveManifestUrl("http://example.com/wmkeyboard-repo.json"))
    }

    @Test
    fun `non-web schemes are rejected`() {
        for (url in listOf(
            "file:///data/data/com.example/wmkeyboard-repo.json",
            "content://com.example/manifest",
            "javascript:alert(1)",
            "ftp://example.com/wmkeyboard-repo.json",
        )) {
            assertNull("should reject $url", AddonRepoCodec.resolveManifestUrl(url))
        }
    }

    @Test
    fun `empty input resolves to nothing`() {
        assertNull(AddonRepoCodec.resolveManifestUrl(""))
        assertNull(AddonRepoCodec.resolveManifestUrl("   "))
    }

    @Test
    fun `a github URL without a repository name is rejected`() {
        assertNull(AddonRepoCodec.resolveManifestUrl("https://github.com/user"))
    }

    @Test
    fun `a repository page on another forge resolves to its raw manifest`() {
        assertEquals(
            "https://codeberg.org/me/addons/raw/branch/main/wmkeyboard-repo.json",
            AddonRepoCodec.resolveManifestUrl("https://codeberg.org/me/addons"),
        )
        assertEquals(
            "https://git.home.example/me/addons/raw/branch/dev/wmkeyboard-repo.json",
            AddonRepoCodec.resolveManifestUrl("https://git.home.example/me/addons/src/branch/dev"),
        )
        assertEquals(
            "https://gitlab.com/group/sub/addons/-/raw/stable/repo/wmkeyboard-repo.json",
            AddonRepoCodec.resolveManifestUrl("gitlab.com/group/sub/addons/-/tree/stable/repo"),
        )
        assertEquals(
            "https://git.sr.ht/~me/addons/blob/main/wmkeyboard-repo.json",
            AddonRepoCodec.resolveManifestUrl("https://git.sr.ht/~me/addons"),
        )
        // A raw manifest address on any forge still passes through untouched.
        val raw = "https://codeberg.org/me/addons/raw/branch/main/wmkeyboard-repo.json"
        assertEquals(raw, AddonRepoCodec.resolveManifestUrl(raw))
    }

    // ---- asset resolution ----------------------------------------------

    private val manifestUrl =
        "https://raw.githubusercontent.com/user/repo/HEAD/wmkeyboard-repo.json"

    @Test
    fun `a relative path resolves against the manifest directory`() {
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/HEAD/themes/midnight.wmtheme.json",
            AddonRepoCodec.resolveAsset(manifestUrl, "themes/midnight.wmtheme.json"),
        )
    }

    @Test
    fun `an absolute https payload URL passes through`() {
        val url = "https://cdn.example.com/pack.wmicons"
        assertEquals(url, AddonRepoCodec.resolveAsset(manifestUrl, url))
    }

    @Test
    fun `an absolute http payload URL is refused`() {
        assertNull(AddonRepoCodec.resolveAsset(manifestUrl, "http://cdn.example.com/pack.wmicons"))
    }

    @Test
    fun `a relative path cannot escape the repository`() {
        // On a raw host, ../.. walks into someone else's repository — a
        // manifest would be serving payloads it does not own.
        for (reference in listOf(
            "../../../other/repo/HEAD/evil.wmtheme.json",
            "../evil.wmtheme.json",
            "themes/../../evil.wmtheme.json",
        )) {
            assertNull("should refuse $reference", AddonRepoCodec.resolveAsset(manifestUrl, reference))
        }
    }

    @Test
    fun `staying inside the repository after a dot-dot is allowed`() {
        // themes/../layouts/x is still under the manifest's own directory.
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/HEAD/layouts/x.wmlayout.json",
            AddonRepoCodec.resolveAsset(manifestUrl, "themes/../layouts/x.wmlayout.json"),
        )
    }

    @Test
    fun `root-relative and protocol-relative references are refused`() {
        // Both skip the repository directory, which the relative form may not do.
        assertNull(AddonRepoCodec.resolveAsset(manifestUrl, "/other/evil.wmtheme.json"))
        assertNull(AddonRepoCodec.resolveAsset(manifestUrl, "//evil.example.com/x.wmtheme.json"))
    }

    @Test
    fun `an empty reference resolves to nothing`() {
        assertNull(AddonRepoCodec.resolveAsset(manifestUrl, ""))
        assertNull(AddonRepoCodec.resolveAsset(manifestUrl, "   "))
    }
}

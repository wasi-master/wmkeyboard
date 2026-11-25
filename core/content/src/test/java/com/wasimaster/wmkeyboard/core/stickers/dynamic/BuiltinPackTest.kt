package com.wasimaster.wmkeyboard.core.stickers.dynamic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The built-in pack as shipped: `app/src/main/assets/dynstickers/`, read
 * through the real [DynStickerStore] the way the keyboard reads it.
 *
 * The pack and the art library are generated (tools/dynstickers/), so this
 * is where a mistake in the generator shows: a scene that does not compile,
 * a drawing a scene names that the library lacks, a manifest whose slots
 * disagree with what its scene compiles to, a phrase its own template
 * cannot take.
 */
class BuiltinPackTest {

    private val assets: File = findAssets()
    private val store = DynStickerStore(null) { path -> File(assets, path).takeIf { it.isFile }?.inputStream() }
    private val pack: DynPackMeta = checkNotNull(store.pack(DynStickerStore.BUILTIN_ID)) { "no built-in pack.json" }
    private val templates: List<DynTemplate> = store.allTemplates()

    private fun sceneText(meta: TemplateMeta): String {
        val path = checkNotNull(meta.scene) { "${meta.id} has no scene" }
        val file = File(assets, "builtin/$path")
        assertTrue("${meta.id}: $path is missing", file.isFile)
        return file.readText()
    }

    @Test
    fun theManifestLoadsWithEveryTemplate() {
        assertEquals(PackSource.BUILTIN, pack.source)
        val ids = pack.templates.map { it.id }
        assertEquals("duplicate ids", ids.size, ids.toSet().size)
        assertTrue("only ${ids.size} templates", ids.size >= 150)
        // A template drops out of allTemplates() when it has no slot.
        assertEquals(ids, templates.map { it.meta.id })
        assertTrue("fewer than 6 generic stickers", pack.templates.count { it.match.generic && it.kind == DynKind.STICKER } >= 6)
        assertTrue("fewer than 15 memes", pack.templates.count { it.kind == DynKind.MEME } >= 15)
    }

    @Test
    fun everySceneCompilesToWhatTheManifestSays() {
        for (template in templates) {
            val meta = template.meta
            val compiled = checkNotNull(store.compileScene(sceneText(meta), meta)) { "${meta.id} does not compile" }
            assertEquals("${meta.id}: manifest slots differ from the scene's", compiled.slots, meta.slots)
            assertEquals("${meta.id}: manifest photos differ from the scene's", compiled.photos, meta.photos)
            val layers = LottieDoc.textLayers(compiled.document).map { it.name }.toSet()
            for (slot in meta.slots) {
                for (layer in slot.layers) assertTrue("${meta.id}: no text layer $layer", layer in layers)
            }
            val doc = checkNotNull(store.document(template)) { "${meta.id}: no document" }
            assertTrue("${meta.id}: no layers", (doc["layers"] as JsonArray).isNotEmpty())
        }
    }

    @Test
    fun everyTemplateHasAMainSlotItsSampleFits() {
        for (template in templates) {
            val main = checkNotNull(template.mainSlot) { "${template.meta.id} has no slot" }
            assertEquals(template.meta.id, TemplateProps.MAIN_SLOT, main.id)
            assertEquals(template.meta.id, SlotRole.MAIN, main.role)
            if (main.sample.isNotBlank()) {
                assertTrue("${template.meta.id}: its own sample '${main.sample}' does not fit", DynMatcher.fits(main, main.sample))
            }
        }
    }

    @Test
    fun everyDrawingAScenesNamesIsInTheLibrary() {
        assertTrue("the art library is empty", store.artIds().size >= 100)
        val used = HashSet<String>()
        for (template in templates) {
            val scene = checkNotNull(store.scene(template)) { "${template.meta.id}'s scene does not parse" }
            for (el in scene.elements.filter { it.type == ElementType.ART }) {
                val id = checkNotNull(el.art) { "${template.meta.id}/${el.id} names no drawing" }
                used += id
                val art = store.artFor(id)
                assertNotNull("${template.meta.id}/${el.id}: no drawing '$id' in art.json", art)
                assertTrue("$id is empty", art!!.shapes.isNotEmpty() && art.width > 0f && art.height > 0f)
            }
        }
        assertTrue("scenes use only ${used.size} drawings", used.size >= 50)
    }

    @Test
    fun everyPhraseParsesAndFitsItsOwnTemplate() {
        for (template in templates) {
            val match = template.meta.match
            for (phrase in match.phrases) {
                val normalized = DynText.normalize(phrase)
                assertTrue("${template.meta.id}: '$phrase' normalises to nothing", normalized.isNotEmpty())
                assertEquals("${template.meta.id}: '$phrase' is not stored normalised", phrase.replace("'", ""), normalized)
                assertNotNull("${template.meta.id}: its own phrase '$phrase' does not fit it", DynMatcher.fillFor(template, phrase))
            }
            for (concept in match.concepts) {
                assertEquals("${template.meta.id}: concept '$concept' is not canonical", EmojiConcepts.canonical(concept), concept)
            }
            assertTrue("${template.meta.id} matches nothing", match.generic || match.phrases.isNotEmpty() || template.kind == DynKind.MEME)
        }
    }

    @Test
    fun commonMessagesFindSeveralTemplatesByMeaning() {
        val matcher = DynMatcher(store.templates())
        val options = DynMatcher.Options(limit = 60)
        val messages = listOf(
            "thank you", "good morning", "happy birthday", "ধন্যবাদ", "শুভ জন্মদিন",
            "lol", "love you", "good night", "ok", "congrats",
        )
        for (message in messages) {
            val hits = matcher.match(message, options)
            val byMeaning = hits.filter { it.tier != DynMatcher.Tier.GENERIC }
            assertTrue("'$message' found ${byMeaning.size} by meaning: ${byMeaning.map { it.template.meta.id }}", byMeaning.size >= 3)
            assertTrue("'$message' offered a meme without asking", hits.none { it.template.kind == DynKind.MEME })
            hits.forEach { assertEquals(DynText.display(message), it.fill.texts[TemplateProps.MAIN_SLOT]) }
        }
        // Any short text still gets the generic ones.
        val generic = matcher.match("pizza party at mine", options).filter { it.tier == DynMatcher.Tier.GENERIC }
        assertTrue("generic templates: ${generic.size}", generic.size >= 6)
        assertTrue(generic.none { it.template.kind == DynKind.MEME })
    }

    @Test
    fun memesSplitAMessageOverTheirSlots() {
        val matcher = DynMatcher(store.templates())
        val all = DynMatcher.Options(limit = 200, memes = true, photos = true)
        val impact = templates.first { it.meta.id == "meme_impact" }
        val fill = checkNotNull(DynMatcher.fillFor(impact, "when the build passes | first try")) { "the impact meme refused two parts" }
        assertEquals("when the build passes", fill.texts[TemplateProps.MAIN_SLOT])
        assertEquals("first try", fill.texts["bottom"])
        assertTrue(impact.meta.photos.isNotEmpty())
        val hits = matcher.match("me | also me", all)
        assertTrue(hits.any { it.template.kind == DynKind.MEME && it.template.meta.photos.isNotEmpty() })
        assertTrue(hits.any { it.template.kind == DynKind.MEME && it.template.meta.photos.isEmpty() })
        val noPhotos = DynMatcher.Options(limit = 200, memes = true)
        assertFalse(matcher.match("me | also me", noPhotos).any { it.template.meta.photos.isNotEmpty() })
    }

    @Test
    fun stillsOnlyEnterAndLoopsCloseOnThemselves() {
        val entrances = setOf(
            AnimPreset.POP_IN, AnimPreset.FADE_IN, AnimPreset.ZOOM_IN,
            AnimPreset.DROP_IN, AnimPreset.SLIDE_IN, AnimPreset.TYPEWRITER,
        )
        for (template in templates) {
            val scene = store.scene(template)!!
            val anims = scene.elements.flatMap { it.animations }
            if (template.meta.stillOnly && scene.animated) {
                anims.forEach { assertTrue("${template.meta.id} is sent still but ${it.preset} loops", it.preset in entrances) }
            }
            if (!scene.animated) continue
            for (anim in anims) {
                if (anim.preset in entrances || anim.preset == AnimPreset.SPIN || !anim.loop) continue
                assertEquals("${template.meta.id}: ${anim.preset} over ${anim.duration} frames jumps when ${scene.frames} frames loop",
                    0, scene.frames % anim.duration)
            }
            for (anim in anims.filter { it.preset in entrances }) {
                assertTrue("${template.meta.id}: ${anim.preset} ends after the last frame", anim.delay + anim.duration <= scene.frames)
            }
        }
    }

    @Test
    fun theArtLibraryIsWellFormed() {
        val root = LottieDoc.json.parseToJsonElement(File(assets, "art/art.json").readText()) as JsonObject
        for ((id, value) in root) {
            val item = value as JsonObject
            fun number(key: String) = (item[key] as JsonPrimitive).content.toFloat()
            assertTrue("$id: no size", number("w") > 0f && number("h") > 0f)
            val shapes = item["shapes"] as JsonArray
            assertTrue("$id: no shapes", shapes.isNotEmpty())
            for (shape in shapes) {
                assertEquals("$id: a top-level item is not a group", "gr", ((shape as JsonObject)["ty"] as JsonPrimitive).content)
            }
        }
        println(
            "built-in pack: ${pack.templates.size} templates; art.json ${File(assets, "art/art.json").length()} bytes, " +
                "${root.size} drawings; pack.json ${File(assets, "builtin/pack.json").length()} bytes; scenes " +
                "${File(assets, "builtin/scenes").listFiles().orEmpty().sumOf { it.length() }} bytes",
        )
    }

    companion object {
        /**
         * `app/src/main/assets/dynstickers`, found from wherever the test runs:
         * the module's directory under Gradle, the repository root or `app/`
         * when run by hand.
         */
        fun findAssets(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                for (candidate in listOf("app/src/main/assets/dynstickers", "src/main/assets/dynstickers")) {
                    val found = File(dir, candidate)
                    if (File(found, "builtin/pack.json").isFile) return found
                }
                dir = dir.parentFile
            }
            error("app/src/main/assets/dynstickers not found above ${File("").absolutePath}")
        }
    }
}

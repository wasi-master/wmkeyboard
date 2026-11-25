package com.wasimaster.wmkeyboard.core.stickers.dynamic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plain-Kotlin half of dynamic stickers: reading templates, fitting text, matching messages, compiling scenes. */
class DynStickerCoreTest {

    /** Every character is 0.6 em wide, tracking ignored: enough to test fitting without a font. */
    private val measurer = TextFit.TextMeasurer { text, _, size, _ -> text.length * size * 0.6f }

    private fun rules(min: Int, max: Int) = { _: Int -> LineRule(minChars = min, maxChars = max) }

    // ---- line splitting --------------------------------------------------

    @Test
    fun splitHonoursEachLinesCharacterRange() {
        val words = listOf("I", "LOVE", "YOU")
        assertEquals(listOf("I LOVE", "YOU"), TextFit.splitLines(words, 2, rules(2, 10)))
    }

    @Test
    fun splitPrefersEvenLines() {
        val words = "thank you so much".split(' ')
        assertEquals(listOf("thank you", "so much"), TextFit.splitLines(words, 2, rules(2, 10)))
    }

    @Test
    fun splitRefusesWhatCannotFit() {
        assertNull(TextFit.splitLines(listOf("thanks"), 2, rules(2, 10)))
        assertNull(TextFit.splitLines(listOf("supercalifragilistic"), 1, rules(2, 10)))
        assertNull(TextFit.splitLines(listOf("a"), 1, rules(2, 10)))
    }

    @Test
    fun oneLineTakesTheWholeText() {
        assertEquals(listOf("good morning all"), TextFit.splitLines("good morning all".split(' '), 1, rules(2, 20)))
    }

    // ---- fitting ---------------------------------------------------------

    private fun boxDoc(size: Float = 200f, boxW: Float = 600f, boxH: Float = 300f, resize: Int? = 2) = LottieDoc.TextDoc(
        text = "HELLO", size = size, font = "F", justify = 2, allCaps = true, lineHeight = size * 1.2f, tracking = 0f,
        boxWidth = boxW, boxHeight = boxH, boxX = -boxW / 2, boxY = -boxH / 2, resize = resize, minFont = 50f, maxFont = 500f,
        maxLines = null, verticalAlign = 1, fill = listOf(1f, 0f, 0f),
    )

    @Test
    fun fitShrinksLongTextIntoTheBox() {
        val fitted = TextFit.fit("A MUCH LONGER MESSAGE THAN THAT", boxDoc(), LineRule(), measurer)
        assertTrue("size ${fitted.size}", fitted.size < 200f)
        fitted.lines.forEach { assertTrue("line '$it' too wide", it.length * fitted.size * 0.6f <= 600f) }
        assertTrue(fitted.size * (1 + (fitted.lines.size - 1) * 1.2f) <= 300f * 1.05f)
    }

    @Test
    fun downscaleOnlyNeverGrows() {
        val fitted = TextFit.fit("HI", boxDoc(resize = 2), LineRule(), measurer)
        assertEquals(200f, fitted.size, 0.01f)
    }

    @Test
    fun scaleToFitGrowsShortText() {
        val fitted = TextFit.fit("HI", boxDoc(resize = 1), LineRule(), measurer)
        assertTrue(fitted.size > 200f)
    }

    @Test
    fun aLineRulesFontBoundsWin() {
        val fitted = TextFit.fit("HI", boxDoc(resize = 1), LineRule(maxFont = 120f), measurer)
        assertEquals(120f, fitted.size, 0.01f)
    }

    // ---- template rules --------------------------------------------------

    private fun textLayer(name: String, text: String, caps: Boolean = true) = LottieDoc.json.parseToJsonElement(
        """{"ty":5,"nm":"$name","ks":{"a":{"a":0,"k":[0,0,0]},"p":{"a":0,"k":[256,256,0]}},
           "t":{"d":{"k":[{"s":{"s":120,"f":"DynaPuff-Black","t":"$text","j":2,"ca":${if (caps) 1 else 0},"lh":140,"tr":0,
           "fc":[1,0,0],"sz":[400,160],"ps":[-200,-80],"rs":2,"mf":40,"xf":300,"vj":1},"t":0}]},"p":{},"m":{"g":1},"a":[]}}""",
    ) as JsonObject

    private fun document(props: String, vararg layers: JsonObject): JsonObject = LottieDoc.json.parseToJsonElement(
        """{"v":"5.7.14","fr":30,"ip":0,"op":60,"w":512,"h":512,"assets":[],
           "fonts":{"list":[{"fName":"DynaPuff-Black","fFamily":"DynaPuff","fStyle":"Black"}]},
           "layers":${JsonArray(layers.toList())},"metadata":{"customProps":$props}}""",
    ) as JsonObject

    private val loveProps = """{"required_lines ":"2","line0-char-min":2,"line0-char-max":10,
        "line1-char-min":"2","line1-char-max":10,"keyword-map":"i love you;love you;","concept-map":"heart;starstuck","alt-text":"animated text"}"""

    @Test
    fun propsReadTheMessyKeysTemplatesShip() {
        val doc = document(loveProps, textLayer("_txt_0", "I LOVE"), textLayer("_txt_1", "YOU"))
        val props = TemplateProps.read(doc)!!
        assertEquals(2, props.requiredLines)
        assertEquals(listOf("i love you", "love you"), props.match.phrases)
        assertEquals(listOf("heart", "starstuck"), props.match.concepts)
        assertEquals(LineRule(2, 10), props.lines[1])
        val slots = TemplateProps.slots(doc, props)
        assertEquals(1, slots.size)
        assertEquals(listOf("_txt_0", "_txt_1"), slots[0].layers)
        assertEquals(TextCase.UPPER, slots[0].case)
    }

    @Test
    fun aStarKeywordMakesAGenericTemplate() {
        val doc = document("""{"keyword-map":"*","concept-map":"default","required-lines":1}""", textLayer("_txt_0", "DEFAULT"))
        val props = TemplateProps.read(doc)!!
        assertTrue(props.match.generic)
        assertTrue(props.match.phrases.isEmpty())
        assertTrue(props.match.concepts.isEmpty())
    }

    @Test
    fun aPlainLottieGetsASlotPerTextLayer() {
        val doc = document("{}", textLayer("Title", "Hello", caps = false), textLayer("Footer", "World", caps = false))
        val slots = TemplateProps.slots(doc, null)
        assertEquals(listOf("Title", "Footer"), slots.map { it.id })
        assertEquals(SlotRole.MAIN, slots[0].role)
    }

    // ---- matching ----------------------------------------------------------

    private fun template(id: String, phrases: List<String> = emptyList(), concepts: List<String> = emptyList(), generic: Boolean = false, lines: Int = 1): DynTemplate {
        val slot = TextSlot(TemplateProps.MAIN_SLOT, (0 until lines).map { "_txt_$it" }, List(lines) { LineRule(2, if (lines == 1) 20 else 10) })
        val meta = TemplateMeta(id = id, match = DynMatch(phrases, concepts, generic))
        return DynTemplate("p", meta, listOf(slot), PackSource.BUILTIN, phrases.map(DynText::normalize))
    }

    private val thanks = template("thanks", phrases = listOf("thanks", "thank you"))
    private val awesome = template("awesome", phrases = listOf("awesome", "thank you"))
    private val laugh = template("laugh", concepts = listOf("laugh"))
    private val anyText = template("any", generic = true)
    private val twoLine = template("love", phrases = listOf("i love you"), lines = 2)
    private val matcher = DynMatcher(listOf(thanks, awesome, laugh, anyText, twoLine))

    @Test
    fun phrasesComeBeforeGenericTemplates() {
        val hits = matcher.match("Thank you!", DynMatcher.Options())
        assertEquals(listOf("awesome", "thanks"), hits.take(2).map { it.template.meta.id }.sorted())
        assertEquals("any", hits.last().template.meta.id)
        assertEquals("Thank you!", hits.first().fill.texts[TemplateProps.MAIN_SLOT])
    }

    @Test
    fun aPhraseInsideTheMessageMatches() {
        val hits = matcher.match("thank you so much", DynMatcher.Options(tiers = setOf(DynMatcher.Tier.INSIDE)))
        assertEquals(setOf("thanks", "awesome"), hits.map { it.template.meta.id }.toSet())
    }

    @Test
    fun emojiAndWordsFindConcepts() {
        val byEmoji = matcher.match("that was it 😂", DynMatcher.Options(tiers = setOf(DynMatcher.Tier.CONCEPT)))
        assertEquals(listOf("laugh"), byEmoji.map { it.template.meta.id })
        val byWord = matcher.match("lol", DynMatcher.Options(tiers = setOf(DynMatcher.Tier.CONCEPT)))
        assertEquals(listOf("laugh"), byWord.map { it.template.meta.id })
        // The emoji never reaches the sticker itself.
        assertEquals("that was it", byEmoji.single().fill.texts[TemplateProps.MAIN_SLOT])
    }

    @Test
    fun templatesTheMessageDoesNotFitAreLeftOut() {
        // One word cannot fill a two-line template.
        val hits = matcher.match("love", DynMatcher.Options())
        assertFalse(hits.any { it.template.meta.id == "love" })
        val love = matcher.match("I love you", DynMatcher.Options())
        assertEquals("love", love.first().template.meta.id)
    }

    @Test
    fun longMessagesGetNothing() {
        assertTrue(matcher.match("one two three four five six seven", DynMatcher.Options(maxWords = 6)).isEmpty())
    }

    @Test
    fun theOrderIsStableForTheSameText() {
        val a = matcher.match("hello there", DynMatcher.Options()).map { it.template.key }
        val b = matcher.match("hello there", DynMatcher.Options()).map { it.template.key }
        assertEquals(a, b)
    }

    @Test
    fun favouritesLeadTheirTier() {
        val hits = matcher.match("thank you", DynMatcher.Options(favourites = setOf("p/awesome")))
        assertEquals("awesome", hits.first().template.meta.id)
    }

    @Test
    fun disabledAndLimitedAreRespected() {
        val hits = matcher.match("thank you", DynMatcher.Options(disabled = setOf("p/thanks"), limit = 1))
        assertEquals(listOf("awesome"), hits.map { it.template.meta.id })
    }

    // ---- text ------------------------------------------------------------

    @Test
    fun normalizingDropsEmojiAndApostrophes() {
        assertEquals("cant wait", DynText.normalize("Can't wait!! 🎉"))
        assertEquals("ধন্যবাদ", DynText.normalize("ধন্যবাদ 🙏"))
        assertEquals(listOf("🎉"), DynText.emoji("Can't wait!! 🎉"))
        assertEquals("Can't wait!!", DynText.display("Can't wait!! 🎉"))
    }

    @Test
    fun bengaliAndEmojiNeedPlatformShaping() {
        assertTrue(DynFiller.needsShaping("ধন্যবাদ"))
        assertTrue(DynFiller.needsShaping("hi 👋🏽"))
        assertFalse(DynFiller.needsShaping("THANK YOU!"))
    }

    // ---- filling ---------------------------------------------------------

    @Test
    fun fillingForLottieFitsAndCasesTheText() {
        val doc = document(loveProps, textLayer("_txt_0", "I LOVE"), textLayer("_txt_1", "YOU"))
        val slots = TemplateProps.slots(doc, TemplateProps.read(doc))
        val template = DynTemplate("p", TemplateMeta("love"), slots, PackSource.FEED, emptyList())
        val result = DynFiller.fill(doc, template, DynFill(mapOf(TemplateProps.MAIN_SLOT to "miss you lots")), DynFiller.Engine.LOTTIE, measurer)
        val layers = LottieDoc.textLayers(result.document).associateBy { it.name }
        assertEquals("MISS YOU", layers.getValue("_txt_0").doc.text)
        assertEquals("LOTS", layers.getValue("_txt_1").doc.text)
        // Cased here, so the document's own all-caps flag is cleared.
        assertFalse(layers.getValue("_txt_0").doc.allCaps)
        assertTrue(layers.getValue("_txt_0").doc.size <= 120f)
    }

    @Test
    fun fillingForTheExactEngineOnlyReplacesWords() {
        val doc = document(loveProps, textLayer("_txt_0", "I LOVE"), textLayer("_txt_1", "YOU"))
        val slots = TemplateProps.slots(doc, TemplateProps.read(doc))
        val template = DynTemplate("p", TemplateMeta("love"), slots, PackSource.FEED, emptyList())
        val result = DynFiller.fill(doc, template, DynFill(mapOf(TemplateProps.MAIN_SLOT to "miss you lots")), DynFiller.Engine.EXACT, measurer)
        val first = LottieDoc.textLayers(result.document).first { it.name == "_txt_0" }.doc
        assertEquals(120f, first.size, 0.01f)
        assertTrue(first.allCaps)
    }

    @Test
    fun aFontOverrideIsAddedToTheFontsList() {
        val doc = document(loveProps, textLayer("_txt_0", "I LOVE"), textLayer("_txt_1", "YOU"))
        val slots = TemplateProps.slots(doc, TemplateProps.read(doc))
        val template = DynTemplate("p", TemplateMeta("love"), slots, PackSource.FEED, emptyList())
        val fill = DynFill(mapOf(TemplateProps.MAIN_SLOT to "hey you"), fontOverride = "Anton")
        val result = DynFiller.fill(doc, template, fill, DynFiller.Engine.LOTTIE, measurer)
        assertTrue(LottieDoc.fonts(result.document).any { it.name == "Anton" })
        assertEquals("Anton", LottieDoc.textLayers(result.document).first().doc.font)
    }

    // ---- scenes ----------------------------------------------------------

    private val scene = DynScene(
        frames = 60,
        elements = listOf(
            SceneElement(id = "burst", type = ElementType.SHAPE, shape = ShapeKind.BURST, w = 0.9f, h = 0.9f, fill = 0xFFFFC107,
                animations = listOf(SceneAnimation(AnimPreset.SPIN, duration = 60))),
            SceneElement(id = "l1", type = ElementType.TEXT, slot = "main", text = "THANK", y = 0.4f, stroke = 0xFFFFFFFF, strokeWidth = 12f,
                animations = listOf(SceneAnimation(AnimPreset.POP_IN, loop = false))),
            SceneElement(id = "l2", type = ElementType.TEXT, slot = "main", text = "YOU", y = 0.6f),
            SceneElement(id = "label", type = ElementType.TEXT, text = "fixed words", y = 0.9f, case = TextCase.KEEP),
            SceneElement(id = "pic", type = ElementType.PHOTO, slot = "face", corner = 24f),
        ),
    )

    @Test
    fun aSceneCompilesToAReadableTemplate() {
        val compiled = SceneToLottie.compile(scene, "Thanks", DynMatch(listOf("thank you"), listOf("heart")))
        val doc = LottieDoc.parse(LottieDoc.encode(compiled.document))!!
        val info = LottieDoc.info(doc)
        assertEquals(60f, info.frames)
        val names = LottieDoc.textLayers(doc).map { it.name }
        assertTrue(names.containsAll(listOf("_txt_0", "_txt_1", "static_label")))
        assertEquals(listOf(TemplateProps.MAIN_SLOT), compiled.slots.map { it.id })
        assertEquals(listOf("_txt_0", "_txt_1"), compiled.slots.single().layers)
        assertEquals(listOf("face"), compiled.photos.map { it.id })
        // The rules ride in the document too, for other readers.
        val props = TemplateProps.read(doc)!!
        assertEquals(listOf("thank you"), props.match.phrases)
        assertEquals(2, props.requiredLines)
        // The fonts list names every font a text layer uses.
        assertTrue(LottieDoc.fonts(doc).any { it.name == SceneElement.DEFAULT_FONT })
        // Lottie lists the top layer first: the photo, added last, comes first.
        val layers = doc["layers"] as JsonArray
        assertEquals(LottieDoc.TYPE_IMAGE, ((layers.first() as JsonObject)["ty"] as JsonPrimitive).content.toInt())
        assertNotNull(LottieDoc.imageAssets(doc).firstOrNull { it.id == SceneToLottie.photoAssetId("face") })
    }

    @Test
    fun motionBecomesKeyframes() {
        val doc = SceneToLottie.compile(scene).document
        val burst = (doc["layers"] as JsonArray).map { it as JsonObject }.first { (it["nm"] as JsonPrimitive).content == "shape_burst" }
        val rotation = (burst["ks"] as JsonObject)["r"] as JsonObject
        assertEquals("1", (rotation["a"] as JsonPrimitive).content)
        val still = SceneToLottie.compile(scene.copy(frames = 1)).document
        val stillBurst = (still["layers"] as JsonArray).map { it as JsonObject }.first { (it["nm"] as JsonPrimitive).content == "shape_burst" }
        assertEquals("0", (((stillBurst["ks"] as JsonObject)["r"] as JsonObject)["a"] as JsonPrimitive).content)
    }

    // ---- packs and feeds -------------------------------------------------

    @Test
    fun aManifestRoundTrips() {
        val pack = DynPackMeta(
            id = "mine", name = "Mine", templates = listOf(TemplateMeta(id = "a", file = "templates/a.json", match = DynMatch(listOf("hi")))),
            fonts = listOf(PackFont("DynaPuff", "fonts/DynaPuff.ttf")),
        )
        assertEquals(pack, DynPackFile.readManifest(DynPackFile.writeManifest(pack)))
        assertNull(DynPackFile.readManifest("""{"format":"wmkeyboard-stickers","pack":{"id":"x"}}"""))
    }

    @Test
    fun ourFeedIndexResolvesRelativeLinks() {
        val index = TemplateFeed.parse(
            """{"format":"wmkeyboard-dynstickers-feed","name":"Party","packs":[{"id":"party","url":"party.wmdynstickers","sha256":"ab"},{"id":"bad","url":"http://x/y"}]}""",
            "https://example.org/feeds/index.json",
        )!!
        assertFalse(index.assembled)
        assertEquals(listOf("https://example.org/feeds/party.wmdynstickers"), index.downloads.map { it.url })
        assertEquals("SHA-256", index.downloads.single().algorithm)
    }

    @Test
    fun theOtherManifestShapeIsAssembledIntoOnePack() {
        val index = TemplateFeed.parse(
            """{"base_download_url":"https://cdn.example.org/art/","download_packing_scheme":"zip","packs":[
               {"name":"1","download_urls":["20240101/fonts.zip"],"md5_checksum":"aa","compressed_size":10,"size":20,"label":"fonts"},
               {"name":"2","download_urls":["20240101/animation.zip"],"md5_checksum":"bb","size":30,"label":"animation"}]}""",
            "https://example.org/manifest.json",
        )!!
        assertTrue(index.assembled)
        assertEquals("20240101", index.version)
        assertEquals(listOf("fonts", "animation"), index.downloads.map { it.label })
        assertEquals("https://cdn.example.org/art/20240101/fonts.zip", index.downloads.first().url)
        assertEquals("MD5", index.downloads.first().algorithm)
    }

    @Test
    fun fontsXmlMapsFamiliesToFiles() {
        val map = FontsXml.parse(
            """<familyset version="23"><family name="Puffy 2021"><font weight="100">Puffy-Black.ttf</font><font weight="900">Puffy-Black.ttf</font></family>
               <family name="Puffy"><font weight="400">Puffy-Black.ttf</font></family></familyset>""",
        )
        assertEquals(listOf("Puffy-Black.ttf"), map["Puffy 2021"])
        assertEquals(listOf("Puffy-Black.ttf"), map["Puffy"])
    }

    @Test
    fun conceptMisspellingsFoldIntoOne() {
        assertEquals("starstruck", EmojiConcepts.canonical("starstuck"))
        assertEquals("embarrassed", EmojiConcepts.canonical(" Embarassed "))
        assertTrue("laugh" in EmojiConcepts.of("😂"))
        assertTrue("heart" in EmojiConcepts.of("❤️"))
    }
}

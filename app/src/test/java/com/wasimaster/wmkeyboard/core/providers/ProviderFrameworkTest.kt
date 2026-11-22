package com.wasimaster.wmkeyboard.core.providers

import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiSettings
import com.wasimaster.wmkeyboard.core.settings.DeepLSettings
import com.wasimaster.wmkeyboard.core.settings.GifSettings
import com.wasimaster.wmkeyboard.core.settings.GifSourceMode
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OcrEngine
import com.wasimaster.wmkeyboard.core.settings.ProviderEdits
import com.wasimaster.wmkeyboard.core.settings.ProviderProblem
import com.wasimaster.wmkeyboard.core.settings.ProviderSettings
import com.wasimaster.wmkeyboard.core.settings.RateSourceSettings
import com.wasimaster.wmkeyboard.core.settings.ScannerSettings
import com.wasimaster.wmkeyboard.core.settings.SelfHostedSettings
import com.wasimaster.wmkeyboard.core.settings.TranslateEngine
import com.wasimaster.wmkeyboard.core.settings.TranslateServerSettings
import com.wasimaster.wmkeyboard.core.settings.TranslateSettings
import com.wasimaster.wmkeyboard.core.settings.WebSearchSettings
import com.wasimaster.wmkeyboard.core.settings.WhisperSettings
import com.wasimaster.wmkeyboard.core.settings.aiThrough
import com.wasimaster.wmkeyboard.core.settings.preferringEngine
import com.wasimaster.wmkeyboard.core.settings.withProviderChoices
import com.wasimaster.wmkeyboard.core.settings.assistHome
import com.wasimaster.wmkeyboard.core.settings.homes
import com.wasimaster.wmkeyboard.core.settings.ocrEngineFromList
import com.wasimaster.wmkeyboard.core.settings.providerChain
import com.wasimaster.wmkeyboard.core.settings.providerChoices
import com.wasimaster.wmkeyboard.core.settings.providerCredentials
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
import com.wasimaster.wmkeyboard.core.tools.DictionarySource
import com.wasimaster.wmkeyboard.core.tools.DictionarySourceChoice
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFrameworkTest {

    private val play = ProviderEnv(ProviderChannel.PLAY, offline = false, onDeviceTranslate = true, offlineGifPacks = false)
    private val github = play.copy(channel = ProviderChannel.GITHUB)
    private val fdroid = play.copy(channel = ProviderChannel.FDROID, onDeviceTranslate = false)
    private val offline = github.copy(offline = true)

    @After
    fun tearDown() {
        ProviderRunner.reset()
        ProviderRunner.clock = { System.nanoTime() / 1_000_000 }
    }

    private fun ids(chain: ProviderChain) = chain.entries.map { it.typeId }
    private fun on(chain: ProviderChain) = chain.entries.filter { it.enabled }.map { it.typeId }

    // ---- codec ----

    @Test
    fun `credentials and lists survive the store`() {
        val credentials = listOf(
            ProviderCredential("klipy", "klipy", apiKey = "k"),
            ProviderCredential("libretranslate:0a0b", "libretranslate", name = "Home", url = "https://lt", apiKey = "x"),
        )
        assertEquals(credentials, ProviderCodec.decodeCredentials(ProviderCodec.encodeCredentials(credentials)))
        val chains = mapOf(
            ProviderTool.GIF to ProviderChain(listOf(ProviderEntry("klipy"), ProviderEntry("gifcities", enabled = false)), ChainMode.MIX),
            ProviderTool.TRANSLATE to ProviderChain(listOf(ProviderEntry("libretranslate", "libretranslate:0a0b"))),
        )
        assertEquals(chains, ProviderCodec.decodeChains(ProviderCodec.encodeChains(chains)))
    }

    @Test
    fun `nothing stored reads as null so the old settings answer`() {
        assertNull(ProviderCodec.decodeCredentials(null))
        assertNull(ProviderCodec.decodeCredentials("not json"))
        assertTrue(ProviderCodec.decodeChains("").isEmpty())
    }

    @Test
    fun `an unknown tool is dropped, an unknown type is kept, a foreign mode falls back`() {
        val raw = """{"gif":{"entries":[{"typeId":"future_gifs"},{"typeId":"klipy"}]},"teleport":{},""" +
            """"translate":{"entries":[],"mode":"TABS"}}"""
        val chains = ProviderCodec.decodeChains(raw)
        assertEquals(setOf(ProviderTool.GIF, ProviderTool.TRANSLATE), chains.keys)
        assertEquals(listOf("future_gifs", "klipy"), ids(chains.getValue(ProviderTool.GIF)))
        assertEquals(ChainMode.FALLBACK, chains.getValue(ProviderTool.TRANSLATE).mode)
    }

    // ---- runner ----

    @Test
    fun `fallback walks past a failure and an empty answer`() = runBlocking {
        val outcome = ProviderRunner.fallback(
            listOf("a", "b", "c"),
            key = { it },
            isEmpty = { it: List<Int> -> it.isEmpty() },
        ) { name ->
            when (name) {
                "a" -> throw IOException("down")
                "b" -> emptyList()
                else -> listOf(1)
            }
        }
        assertEquals(ProviderRunner.Outcome.Found(listOf(1), "c"), outcome)
    }

    @Test
    fun `not found and failed stay apart`() = runBlocking {
        val none = ProviderRunner.fallback(listOf("a"), key = { it }, isEmpty = { it: String -> it.isEmpty() }) { "" }
        assertEquals(ProviderRunner.Outcome.NotFound, none)
        val failed = ProviderRunner.fallback<String, String>(listOf("a"), key = { it }) { throw IOException("x") }
        assertTrue(failed is ProviderRunner.Outcome.Failed)
    }

    @Test
    fun `an unreachable provider cools down while another can answer`() = runBlocking {
        var now = 0L
        ProviderRunner.clock = { now }
        val asked = mutableListOf<String>()
        suspend fun ask() = ProviderRunner.fallback(listOf("a", "b"), key = { it }) { name ->
            asked += name
            if (name == "a") throw UnknownHostException("a") else name
        }
        ask()
        ask()
        assertEquals(listOf("a", "b", "b"), asked)
        now += ProviderRunner.COOLDOWN_MS
        ask()
        assertEquals(listOf("a", "b", "b", "a", "b"), asked)
    }

    @Test
    fun `when every provider is cooling down they are asked anyway`() = runBlocking {
        ProviderRunner.clock = { 0L }
        var calls = 0
        repeat(2) {
            ProviderRunner.fallback<String, String>(listOf("a"), key = { it }) {
                calls++
                throw UnknownHostException("a")
            }
        }
        assertEquals(2, calls)
    }

    @Test
    fun `a wrong request does not cool a provider down`() = runBlocking {
        ProviderRunner.clock = { 0L }
        ProviderRunner.fallback(listOf("a", "b"), key = { it }) { if (it == "a") throw IllegalStateException() else it }
        assertFalse(ProviderRunner.coolingDown("a", 0L))
    }

    @Test
    fun `interleave takes one from each in turn`() {
        assertEquals(listOf(1, 10, 2, 20, 3), ProviderRunner.interleave(listOf(listOf(1, 2, 3), listOf(10, 20))))
    }

    // ---- today's lists, read from the old settings ----

    @Test
    fun `the gif list is the keyed providers, then Commons on F-Droid, then GifCities, then the packs`() {
        val s = KeyboardSettings(gif = GifSettings(klipyApiKey = "k", gifCities = true, sourceMode = GifSourceMode.MIX))
        val chain = s.providerChain(ProviderTool.GIF, fdroid)
        assertEquals("klipy", chain.entries.first().typeId)
        val tail = ids(chain).dropWhile { it == "klipy" || it == "giphy" }
        assertEquals(listOf("commons", "gifcities", "offline_gif_packs"), tail)
        assertEquals(ChainMode.MIX, chain.mode)
        assertFalse("commons" in ids(s.providerChain(ProviderTool.GIF, play)))
    }

    @Test
    fun `GifCities stays out until its switch was on`() {
        assertFalse("gifcities" in ids(KeyboardSettings().providerChain(ProviderTool.GIF, play)))
    }

    @Test
    fun `stickers no longer carry an always empty Commons`() {
        val chain = KeyboardSettings().providerChain(ProviderTool.STICKER, fdroid)
        assertFalse("commons" in ids(chain))
        assertEquals("sticker_packs", chain.entries.last().typeId)
    }

    @Test
    fun `translate lists the device first, then the online services`() {
        val s = KeyboardSettings(
            translate = TranslateSettings(engine = TranslateEngine.AUTO, deepl = DeepLSettings(apiKey = "d")),
            selfHosted = SelfHostedSettings(libreTranslateUrl = "https://lt"),
        )
        assertEquals(
            listOf("mlkit", "deepl", "libretranslate", "google_translate"),
            on(s.providerChain(ProviderTool.TRANSLATE, play)),
        )
    }

    @Test
    fun `the engine switch stays out of the list`() {
        val online = KeyboardSettings(translate = TranslateSettings(engine = TranslateEngine.ONLINE))
        val device = KeyboardSettings(translate = TranslateSettings(engine = TranslateEngine.ON_DEVICE))
        assertEquals(online.providerChain(ProviderTool.TRANSLATE, play), device.providerChain(ProviderTool.TRANSLATE, play))
        assertEquals(listOf("mlkit", "google_translate"), ids(online.providerChain(ProviderTool.TRANSLATE, github)))
    }

    @Test
    fun `a build with no ML Kit has no on device row`() {
        val s = KeyboardSettings(translate = TranslateSettings(engine = TranslateEngine.ON_DEVICE))
        assertEquals(listOf("google_translate"), ids(s.providerChain(ProviderTool.TRANSLATE, fdroid)))
    }

    @Test
    fun `Bergamot is the on device row without ML Kit, and comes after it with`() {
        val s = KeyboardSettings(translate = TranslateSettings(engine = TranslateEngine.ON_DEVICE))
        assertEquals(
            listOf("bergamot", "google_translate"),
            ids(s.providerChain(ProviderTool.TRANSLATE, fdroid.copy(bergamot = true))),
        )
        assertEquals(
            listOf("mlkit", "bergamot", "google_translate"),
            ids(s.providerChain(ProviderTool.TRANSLATE, github.copy(bergamot = true))),
        )
        // Android 8 and older, or no internet: no Bergamot.
        assertFalse(ProviderCatalog.runnable(ProviderTypes.BERGAMOT, github))
        assertTrue(ProviderCatalog.runnable(ProviderTypes.BERGAMOT, github.copy(bergamot = true)))
    }

    @Test
    fun `NLLB comes after the other device engines, and only where it can run`() {
        val s = KeyboardSettings(translate = TranslateSettings(engine = TranslateEngine.ON_DEVICE))
        assertEquals(
            listOf("mlkit", "bergamot", "nllb", "google_translate"),
            ids(s.providerChain(ProviderTool.TRANSLATE, github.copy(bergamot = true, nllb = true))),
        )
        assertEquals(
            listOf("bergamot", "nllb", "google_translate"),
            ids(s.providerChain(ProviderTool.TRANSLATE, fdroid.copy(bergamot = true, nllb = true))),
        )
        // A 32-bit phone, too little memory, or no internet: no NLLB.
        assertFalse(ProviderCatalog.runnable(ProviderTypes.NLLB, github))
        assertTrue(ProviderCatalog.runnable(ProviderTypes.NLLB, github.copy(nllb = true)))
    }

    @Test
    fun `a saved translate list gains a new device engine after the ones it has`() {
        val known = ProviderTypes.serving(ProviderTool.TRANSLATE).map { it.id } - "bergamot"
        val withMlKit = ProviderChain(
            listOf(ProviderEntry("mlkit"), ProviderEntry("deepl"), ProviderEntry("google_translate")),
            known = known,
        )
        assertEquals(
            listOf("mlkit", "bergamot", "deepl", "google_translate"),
            ids(withMlKit.withNewTypes(ProviderTool.TRANSLATE)),
        )
        val onlineOnly = ProviderChain(listOf(ProviderEntry("google_translate")), known = known)
        assertEquals(listOf("bergamot", "google_translate"), ids(onlineOnly.withNewTypes(ProviderTool.TRANSLATE)))
        // Removed by the user after it existed: stays removed.
        val removed = ProviderChain(listOf(ProviderEntry("google_translate")), known = known + "bergamot")
        assertEquals(listOf("google_translate"), ids(removed.withNewTypes(ProviderTool.TRANSLATE)))
    }

    @Test
    fun `the user's own translation server still answers alone`() {
        val s = KeyboardSettings(
            translate = TranslateSettings(engine = TranslateEngine.ONLINE, server = TranslateServerSettings(url = "http://box:8080")),
        )
        assertEquals(listOf("translate_server"), on(s.providerChain(ProviderTool.TRANSLATE, fdroid)))
    }

    @Test
    fun `DeepL with its translate switch off stays out`() {
        val s = KeyboardSettings(translate = TranslateSettings(deepl = DeepLSettings(apiKey = "d", translate = false)))
        assertFalse("deepl" in ids(s.providerChain(ProviderTool.TRANSLATE, fdroid)))
    }

    @Test
    fun `search keeps its old order`() {
        val s = KeyboardSettings(
            webSearch = WebSearchSettings(braveApiKey = "b", tavilyApiKey = "t"),
            selfHosted = SelfHostedSettings(searxUrl = "https://sx"),
        )
        assertEquals(listOf("searxng", "tavily", "brave", "metasearch"), ids(s.providerChain(ProviderTool.SEARCH, play)))
    }

    @Test
    fun `search works with nothing set up`() {
        // Brave may come first, on a build that carries its key.
        assertEquals("metasearch", ids(KeyboardSettings().providerChain(ProviderTool.SEARCH, fdroid)).last())
        assertTrue("metasearch" in KeyboardSettings().resolveProviders(ProviderTool.SEARCH, fdroid).map { it.typeId })
    }

    @Test
    fun `a plugin row is usable only while its plugin is, and is offered by name`() {
        var on = true
        val key = "plugin:com.example.ddg/search/web"
        PluginProviders.install(object : PluginProviders.Directory {
            override fun rows() = listOf(PluginProviders.Row(key, ProviderTool.SEARCH, "DDG", "", "DDG plugin", runnable = on))
            override fun call(key: String, request: kotlinx.serialization.json.JsonObject, isActive: () -> Boolean) =
                kotlinx.serialization.json.JsonNull
        })
        val chain = ProviderChain(listOf(PluginProviders.entry(key)))
        val s = KeyboardSettings(providers = ProviderSettings(chains = mapOf(ProviderTool.SEARCH to chain)))
        val row = s.resolveProviders(ProviderTool.SEARCH, play, all = true).single()
        assertEquals(null, row.problem)
        assertEquals("DDG", row.customName)
        on = false
        assertEquals(ProviderProblem.PLUGIN_OFF, s.resolveProviders(ProviderTool.SEARCH, play, all = true).single().problem)
        // Never offered as a bare kind; by name, until the list has it.
        assertFalse(ProviderTypes.PLUGIN in ProviderCatalog.addable(ProviderTool.SEARCH, ProviderChain(), play))
        assertEquals(listOf(key), ProviderCatalog.addablePlugins(ProviderTool.SEARCH, ProviderChain(), play).map { it.key })
        assertTrue(ProviderCatalog.addablePlugins(ProviderTool.SEARCH, chain, play).isEmpty())
        assertTrue(ProviderCatalog.addablePlugins(ProviderTool.TRANSLATE, ProviderChain(), play).isEmpty())
    }

    @Test
    fun `a search list saved before the metasearch gains it, once`() {
        val before = ProviderChain(listOf(ProviderEntry("tavily")), known = listOf("searxng", "tavily", "brave"))
        assertEquals(listOf("tavily", "metasearch"), before.withNewTypes(ProviderTool.SEARCH).entries.map { it.typeId })
        // Removed by the user after it came: stamped as known, so it stays away.
        val removed = before.copy(known = before.known + "metasearch")
        assertEquals(listOf("tavily"), removed.withNewTypes(ProviderTool.SEARCH).entries.map { it.typeId })
    }

    @Test
    fun `the dictionary list reads straight across`() {
        val s = KeyboardSettings(
            dictionarySources = listOf(
                DictionarySourceChoice(DictionarySource.DICTIONARY_API),
                DictionarySourceChoice(DictionarySource.VOCAB_PACKS, enabled = false),
            ),
        )
        val chain = s.providerChain(ProviderTool.DICTIONARY, play)
        assertEquals(listOf("dictionary_api", "vocab_packs"), ids(chain))
        assertEquals(listOf("dictionary_api"), on(chain))
    }

    @Test
    fun `currency reads the stored names, and an empty list falls back to the defaults`() {
        val s = KeyboardSettings(rateSources = RateSourceSettings(fiatProviders = listOf("FRANKFURTER", "COINGECKO")))
        // CoinGecko serves no fiat, so it never was in the fiat chain.
        assertEquals(listOf("frankfurter"), ids(s.providerChain(ProviderTool.CURRENCY_FIAT, play)))
        val empty = KeyboardSettings(rateSources = RateSourceSettings(fiatProviders = emptyList()))
        assertEquals(listOf("currency_api", "frankfurter"), ids(empty.providerChain(ProviderTool.CURRENCY_FIAT, fdroid)))
        assertEquals(listOf("er_api", "frankfurter"), ids(empty.providerChain(ProviderTool.CURRENCY_FIAT, play)))
    }

    @Test
    fun `extra survives the codec, and a credential without it reads as empty`() {
        val home = ProviderCredential(
            "home_assistant:01", "home_assistant", name = "Flat", url = "ha.local",
            extra = mapOf(HomeKeys.ACCESS_TOKEN to "t", HomeKeys.EXTERNAL_URL to "https://x.ui.nabu.casa"),
        )
        val back = ProviderCodec.decodeCredentials(ProviderCodec.encodeCredentials(listOf(home)))!!.single()
        assertEquals(home, back)
        val old = ProviderCodec.decodeCredentials("""[{"id":"klipy","typeId":"klipy","apiKey":"k"}]""")!!.single()
        assertTrue(old.extra.isEmpty())
        // Nothing to say: an empty map is left out of the JSON.
        assertFalse(ProviderCodec.encodeCredentials(listOf(old)).contains("extra"))
    }

    @Test
    fun `the smart home list starts empty, and a home says what it still needs`() {
        assertTrue(KeyboardSettings().providerChain(ProviderTool.SMART_HOME, play).entries.isEmpty())
        assertEquals(ChainMode.MIX, KeyboardSettings().providerChain(ProviderTool.SMART_HOME, play).mode)

        val withHomes = KeyboardSettings(
            providers = ProviderSettings(
                credentials = listOf(
                    ProviderCredential("home_assistant:01", "home_assistant", url = "ha.local"),
                    ProviderCredential("home_assistant:02", "home_assistant", url = "ha2.local", apiKey = "LONG"),
                    ProviderCredential("smartthings", "smartthings"),
                    ProviderCredential("hue:01", "hue", url = "192.168.1.9"),
                    ProviderCredential("openhab:01", "openhab", url = "http://oh:8080", apiKey = "tok"),
                    ProviderCredential("google_home", "google_home", extra = mapOf(HomeKeys.GRANTED to "1", HomeKeys.STRUCTURE_ID to "s")),
                ),
                chains = mapOf(
                    ProviderTool.SMART_HOME to ProviderChain(
                        listOf(
                            ProviderEntry("home_assistant", "home_assistant:01"),
                            ProviderEntry("home_assistant", "home_assistant:02"),
                            ProviderEntry("smartthings"),
                            ProviderEntry("hue", "hue:01"),
                            ProviderEntry("openhab", "openhab:01"),
                            ProviderEntry("google_home"),
                        ),
                        ChainMode.MIX,
                    ),
                ),
            ),
        )
        val rows = withHomes.resolveProviders(ProviderTool.SMART_HOME, play, all = true).associate { it.key to it.problem }
        assertEquals(ProviderProblem.NEEDS_SIGN_IN, rows["home_assistant:01"])
        assertNull(rows["home_assistant:02"])
        assertEquals(ProviderProblem.NEEDS_SIGN_IN, rows["smartthings"])
        assertEquals(ProviderProblem.NEEDS_SIGN_IN, rows["hue:01"])
        assertNull(rows["openhab:01"])
        // No Home APIs in this build: unavailable, not "sign in".
        assertEquals(ProviderProblem.UNAVAILABLE, rows["google_home"])
        assertNull(withHomes.resolveProviders(ProviderTool.SMART_HOME, play.copy(googleHome = true), all = true).first { it.key == "google_home" }.problem)

        val homes = withHomes.homes(play.copy(googleHome = true))
        assertEquals(listOf("home_assistant:02", "openhab:01", "google_home"), homes.map { it.key })
        assertEquals(HomeKind.OPENHAB, homes[1].kind)
        assertEquals("tok", homes[1].apiKey)
        assertEquals("home_assistant:02", withHomes.assistHome(play)!!.key)
        assertTrue(withHomes.homes(play).none { it.kind == HomeKind.GOOGLE_HOME })
    }

    @Test
    fun `a stored list wins over the old settings`() {
        val stored = ProviderChain(listOf(ProviderEntry("gifcities")))
        val s = KeyboardSettings(
            gif = GifSettings(klipyApiKey = "k"),
            providers = ProviderSettings(chains = mapOf(ProviderTool.GIF to stored)),
        )
        assertEquals(stored, s.providerChain(ProviderTool.GIF, play))
    }

    @Test
    fun `a word source a later build adds joins a saved list, a removed one stays out`() {
        val known = ProviderTypes.serving(ProviderTool.SYNONYMS).map { it.id } - "similar_words"
        val saved = ProviderChain(listOf(ProviderEntry("datamuse")), known = known)
        val s = KeyboardSettings(providers = ProviderSettings(chains = mapOf(ProviderTool.SYNONYMS to saved)))
        assertEquals(listOf("datamuse", "similar_words"), ids(s.providerChain(ProviderTool.SYNONYMS, play)))
        val stamped = saved.stamped(ProviderTool.SYNONYMS)
        assertEquals(listOf("datamuse"), ids(stamped.withNewTypes(ProviderTool.SYNONYMS)))
    }

    // ---- resolution ----

    @Test
    fun `the user's key wins, a missing one is named`() {
        val s = KeyboardSettings(
            providers = ProviderSettings(
                credentials = listOf(ProviderCredential("klipy", "klipy", apiKey = "mine")),
                chains = mapOf(ProviderTool.SEARCH to ProviderChain(listOf(ProviderEntry("tavily")))),
            ),
        )
        val tavily = s.resolveProviders(ProviderTool.SEARCH, play, all = true).single()
        assertEquals(ProviderProblem.NEEDS_KEY, tavily.problem)
        assertTrue(s.resolveProviders(ProviderTool.SEARCH, play).isEmpty())
        val klipy = s.resolveProviders(ProviderTool.GIF, play).first()
        assertEquals("mine", klipy.apiKey)
        assertFalse(klipy.usesBuiltInKey)
    }

    @Test
    fun `the no internet build asks only what is on the phone`() {
        val s = KeyboardSettings(gif = GifSettings(klipyApiKey = "k"))
        val rows = s.resolveProviders(ProviderTool.GIF, offline.copy(offlineGifPacks = true), all = true)
        assertEquals(ProviderProblem.UNAVAILABLE, rows.first { it.typeId == "klipy" }.problem)
        assertEquals(
            listOf("offline_gif_packs"),
            s.resolveProviders(ProviderTool.GIF, offline.copy(offlineGifPacks = true)).map { it.typeId },
        )
    }

    @Test
    fun `offline packs wait for a pack, DeepL takes a proxy alone`() {
        val s = KeyboardSettings(translate = TranslateSettings(deepl = DeepLSettings(endpoint = "https://proxy")))
        assertEquals(
            ProviderProblem.NEEDS_PACKS,
            s.resolveProviders(ProviderTool.GIF, play, all = true).first { it.typeId == "offline_gif_packs" }.problem,
        )
        assertTrue(s.resolveProviders(ProviderTool.TRANSLATE, fdroid).any { it.typeId == "deepl" })
    }

    // ---- pick mode (#727) ----

    @Test
    fun `pick mode asks only the chosen row, and the first when the choice is gone`() {
        val chain = ProviderChain(
            listOf(ProviderEntry("deepl"), ProviderEntry("google_translate"), ProviderEntry("mozhi", "mozhi:01", enabled = false)),
            ChainMode.PICK,
        )
        val s = KeyboardSettings(
            providers = ProviderSettings(
                listOf(
                    ProviderCredential("deepl", "deepl", apiKey = "d"),
                    ProviderCredential("mozhi:01", "mozhi", url = "https://mozhi"),
                ),
                mapOf(ProviderTool.TRANSLATE to chain),
            ),
        )
        val picked = { pick: String? -> s.resolveProviders(ProviderTool.TRANSLATE, play, pick = pick).map { it.key } }
        assertEquals(listOf("google_translate"), picked("google_translate"))
        assertEquals(listOf("deepl"), picked(""))
        // A row that is off cannot be chosen; the first that is on stands in.
        assertEquals(listOf("deepl"), picked("mozhi:01"))
        // No panel (the AI's tools): the whole list, in order.
        assertEquals(listOf("deepl", "google_translate"), picked(null))
        val choices = s.providerChoices(ProviderTool.TRANSLATE, "google_translate", play)!!
        assertEquals("google_translate", choices.chosen)
        assertTrue(choices.shown)
        // In fallback mode the panel has no chooser.
        val fallback = s.copy(providers = s.providers.copy(chains = mapOf(ProviderTool.TRANSLATE to chain.copy(mode = ChainMode.FALLBACK))))
        assertNull(fallback.providerChoices(ProviderTool.TRANSLATE, "", play))
        assertEquals(listOf("deepl", "google_translate"), fallback.resolveProviders(ProviderTool.TRANSLATE, play, pick = "google_translate").map { it.key })
    }

    @Test
    fun `a pick mode a tool does not offer reads as its default`() {
        val chains = ProviderCodec.decodeChains(
            ProviderCodec.encodeChains(mapOf(ProviderTool.CURRENCY_FIAT to ProviderChain(mode = ChainMode.PICK))),
        )
        assertEquals(ChainMode.FALLBACK, chains.getValue(ProviderTool.CURRENCY_FIAT).mode)
    }

    @Test
    fun `the OCR tool's old engine and online reader become its list, and a vision reader needs a model`() {
        val s = KeyboardSettings(scanner = ScannerSettings(ocrOnlineUrl = "https://api", ocrOnlineModel = "", ocrOnlineKey = "k"))
        assertEquals(listOf("ocr_on_device", "vision_ocr"), ids(s.providerChain(ProviderTool.OCR, play)))
        assertEquals(OcrEngine.AUTO, s.ocrEngineFromList(play, OcrEngine.AUTO))
        val online = s.copy(scanner = s.scanner.copy(ocrEngine = OcrEngine.ONLINE))
        val chain = online.providerChain(ProviderTool.OCR, play)
        assertEquals(listOf("vision_ocr", "ocr_on_device"), ids(chain))
        assertFalse(chain.entries.last().enabled)
        val row = s.resolveProviders(ProviderTool.OCR, play, all = true).single { it.typeId == "vision_ocr" }
        assertEquals(ProviderProblem.NEEDS_MODEL, row.problem)
        assertEquals("k", row.apiKey)
        assertTrue(KeyboardSettings().providerChain(ProviderTool.PHOTO_TRANSLATE, play).entries.isEmpty())
    }

    // ---- edits ----

    @Test
    fun `a removed row takes its credential along unless another tool reads it`() {
        val s = KeyboardSettings(gif = GifSettings(klipyApiKey = "k"))
        val fromStickers = ProviderEdits.removeEntry(s, ProviderTool.STICKER, "klipy", play)
        assertNull(fromStickers.credentials)
        assertFalse(fromStickers.chain!!.has("klipy"))

        val server = ProviderCredential("libretranslate:01", "libretranslate", url = "https://lt")
        val added = ProviderEdits.saveCredential(s, server, ProviderTool.TRANSLATE, fdroid)
        val withServer = s.copy(
            providers = ProviderSettings(added.credentials, mapOf(ProviderTool.TRANSLATE to added.chain!!)),
        )
        assertTrue(withServer.providerChain(ProviderTool.TRANSLATE, fdroid).has("libretranslate:01"))
        val removed = ProviderEdits.removeEntry(withServer, ProviderTool.TRANSLATE, "libretranslate:01", fdroid)
        assertFalse(removed.credentials!!.any { it.id == "libretranslate:01" })
    }

    @Test
    fun `a new translation server answers alone until the others are switched back on`() {
        val s = KeyboardSettings(translate = TranslateSettings(deepl = DeepLSettings(apiKey = "d")))
        val server = ProviderCredential("translate_server:01", "translate_server", url = "http://box")
        val chain = ProviderEdits.saveCredential(s, server, ProviderTool.TRANSLATE, play).chain!!
        assertEquals(listOf("mlkit", "translate_server", "deepl", "google_translate"), ids(chain))
        assertEquals(listOf("mlkit", "translate_server"), on(chain))
    }

    @Test
    fun `saving the first credential keeps the old ones`() {
        val s = KeyboardSettings(webSearch = WebSearchSettings(braveApiKey = "b"))
        val change = ProviderEdits.saveCredential(s, ProviderCredential("klipy", "klipy", apiKey = "k"), null, play)
        assertEquals(setOf("brave", "klipy"), change.credentials!!.map { it.id }.toSet())
        assertEquals(setOf("brave"), s.providerCredentials().map { it.id }.toSet())
    }

    // ---- catalogue ----

    @Test
    fun `the no internet build offers only what runs on the phone`() {
        val types = ProviderCatalog.addable(ProviderTool.GIF, ProviderChain(), offline)
        assertEquals(listOf("offline_gif_packs"), types.map { it.id })
    }

    @Test
    fun `every build offers the same kinds in the same order`() {
        val gif = { env: ProviderEnv -> ProviderCatalog.addable(ProviderTool.GIF, ProviderChain(), env).map { it.id } }
        assertEquals(gif(play), gif(fdroid))
        assertEquals(gif(play), gif(github))
        assertEquals(ProviderTypes.serving(ProviderTool.GIF).map { it.id }, gif(play))
    }

    @Test
    fun `a keyed kind can be listed twice where the list asks rows by credential`() {
        val chain = ProviderChain(listOf(ProviderEntry("google_translate"), ProviderEntry("libretranslate", "libretranslate:01")))
        val offered = ProviderCatalog.addable(ProviderTool.TRANSLATE, chain, github).map { it.id }
        assertTrue("google_translate" in offered)
        assertTrue("libretranslate" in offered)
        // The photo grid asks a service by its name, so a second Unsplash would never be asked.
        val photos = ProviderCatalog.addable(ProviderTool.PHOTOS, ProviderChain(listOf(ProviderEntry("unsplash"))), github).map { it.id }
        assertFalse("unsplash" in photos)
        // Nothing to set up, nothing to repeat.
        val gif = ProviderCatalog.addable(ProviderTool.GIF, ProviderChain(listOf(ProviderEntry("gifcities"))), github).map { it.id }
        assertFalse("gifcities" in gif)
    }

    // ---- two of a kind ----

    private fun twoKlipy(mode: ChainMode, sameKind: SameKind? = null) = KeyboardSettings(
        providers = ProviderSettings(
            credentials = listOf(
                ProviderCredential("klipy", "klipy", apiKey = "first"),
                ProviderCredential("klipy:02", "klipy", name = "Work", apiKey = "second"),
            ),
            chains = mapOf(
                ProviderTool.GIF to ProviderChain(
                    listOf(ProviderEntry("klipy"), ProviderEntry("gifcities"), ProviderEntry("klipy", "klipy:02")),
                    mode,
                    sameKind = sameKind,
                ),
            ),
        ),
    )

    @Test
    fun `a second key of a kind is a backup by default, and can be kept apart`() {
        val grouped = twoKlipy(ChainMode.TABS).resolveProviders(ProviderTool.GIF, play, grouped = true)
        assertEquals(listOf("klipy", "gifcities"), grouped.map { it.key })
        assertEquals(listOf("klipy:02"), grouped.first().backups.map { it.key })
        assertEquals(listOf("first", "second"), grouped.first().withBackups.map { it.apiKey })
        val apart = twoKlipy(ChainMode.TABS, SameKind.SEPARATE).resolveProviders(ProviderTool.GIF, play, grouped = true)
        assertEquals(listOf("klipy", "gifcities", "klipy:02"), apart.map { it.key })
        // One at a time is a fallback already: nothing to fold.
        val walk = twoKlipy(ChainMode.FALLBACK).resolveProviders(ProviderTool.GIF, play, grouped = true)
        assertEquals(listOf("klipy", "gifcities", "klipy:02"), walk.map { it.key })
    }

    @Test
    fun `two servers of a server kind stay apart unless the user says otherwise`() {
        val chain = ProviderChain(listOf(ProviderEntry("commons", "commons:01"), ProviderEntry("commons", "commons:02")), ChainMode.MIX)
        assertEquals(SameKind.SEPARATE, chain.sameKindInForce)
        assertEquals(SameKind.FALLBACK, chain.copy(sameKind = SameKind.FALLBACK).sameKindInForce)
        assertTrue(chain.repeatsKind)
        assertFalse(ProviderChain(listOf(ProviderEntry("klipy"))).repeatsKind)
    }

    @Test
    fun `a second SmartThings account can be added, and homes are never folded`() {
        val chain = ProviderChain(listOf(ProviderEntry("smartthings"), ProviderEntry("google_home")), ChainMode.MIX)
        val offered = ProviderCatalog.addable(ProviderTool.SMART_HOME, chain, play.copy(googleHome = true)).map { it.id }
        assertTrue("smartthings" in offered)
        assertFalse("google_home" in offered)
        assertFalse(ProviderTool.SMART_HOME.foldsSameKind(ChainMode.MIX))
        assertFalse(ProviderTool.PHOTOS.foldsSameKind(ChainMode.MIX))
        assertTrue(ProviderTool.GIF.foldsSameKind(ChainMode.TABS))
        assertTrue(ProviderTool.TRANSLATE.foldsSameKind(ChainMode.PICK))
    }

    @Test
    fun `a chosen kind in pick mode is asked with its backups`() {
        val s = KeyboardSettings(
            providers = ProviderSettings(
                listOf(ProviderCredential("deepl", "deepl", apiKey = "a"), ProviderCredential("deepl:02", "deepl", apiKey = "b")),
                mapOf(
                    ProviderTool.TRANSLATE to ProviderChain(
                        listOf(ProviderEntry("deepl"), ProviderEntry("google_translate"), ProviderEntry("deepl", "deepl:02")),
                        ChainMode.PICK,
                    ),
                ),
            ),
        )
        assertEquals(listOf("deepl", "deepl:02"), s.resolveProviders(ProviderTool.TRANSLATE, play, pick = "deepl").map { it.key })
        assertEquals(listOf("deepl", "google_translate"), s.providerChoices(ProviderTool.TRANSLATE, "", play)!!.rows.map { it.key })
    }

    @Test
    fun `a second instance brings its own key and gets a row of its own`() {
        val s = KeyboardSettings(
            providers = ProviderSettings(
                credentials = listOf(ProviderCredential("brave:02", "brave")),
                chains = mapOf(ProviderTool.SEARCH to ProviderChain(listOf(ProviderEntry("brave"), ProviderEntry("brave", "brave:02")))),
            ),
        )
        val second = s.resolveProviders(ProviderTool.SEARCH, play, all = true).last()
        assertFalse(second.usesBuiltInKey)
        if (ProviderTypes.BRAVE.credential.key == Need.REQUIRED) assertEquals(ProviderProblem.NEEDS_KEY, second.problem)
        val change = ProviderEdits.saveCredential(
            KeyboardSettings(),
            ProviderCredential("deepl:03", "deepl", apiKey = "k"),
            ProviderTool.TRANSLATE,
            play,
        )
        assertTrue(change.chain!!.entries.any { it.typeId == "deepl" && it.credentialId == "deepl:03" })
    }

    // ---- AI and voice typing ----

    @Test
    fun `the AI services set up before the list become its rows, the chosen one in use`() {
        val s = KeyboardSettings(
            ai = AiSettings(provider = AiProvider.OLLAMA, anthropicKey = "sk", anthropicModel = "m1", ollamaUrl = "http://pc:11434"),
        )
        val chain = s.providerChain(ProviderTool.AI, github)
        assertEquals(ChainMode.PICK, chain.mode)
        assertEquals(listOf("anthropic", "ollama"), ids(chain))
        val live = s.withProviderChoices(github).ai
        assertEquals(AiProvider.OLLAMA, live.provider)
        assertEquals("ollama", live.providerKey)
        assertEquals("http://pc:11434", live.ollamaUrl)
        assertTrue(live.listed)
        // The stored choice keeps the list it came from, whatever the overlay wrote.
        assertEquals(listOf("anthropic", "ollama"), ids(s.withProviderChoices(github).providerChain(ProviderTool.AI, github)))
    }

    @Test
    fun `a picked second key of one service is what the client reads`() {
        val s = KeyboardSettings(
            ai = AiSettings(provider = AiProvider.ANTHROPIC, providerKey = "anthropic:02"),
            providers = ProviderSettings(
                credentials = listOf(
                    ProviderCredential("anthropic", "anthropic", apiKey = "first"),
                    ProviderCredential("anthropic:02", "anthropic", name = "Work", apiKey = "second", model = "big"),
                ),
                chains = mapOf(
                    ProviderTool.AI to ProviderChain(
                        listOf(ProviderEntry("anthropic"), ProviderEntry("anthropic", "anthropic:02")),
                        ChainMode.PICK,
                    ),
                ),
            ),
        )
        val live = s.withProviderChoices(github).ai
        assertEquals("second", live.anthropicKey)
        assertEquals("big", live.anthropicModel)
        assertEquals("first", s.aiThrough("anthropic", github)!!.anthropicKey)
    }

    @Test
    fun `an emptied AI list is not set up whatever old keys remain`() {
        val s = KeyboardSettings(
            ai = AiSettings(provider = AiProvider.OPENAI, openAiKey = "old"),
            providers = ProviderSettings(chains = mapOf(ProviderTool.AI to ProviderChain(emptyList(), ChainMode.PICK))),
        )
        assertFalse(s.withProviderChoices(github).ai.listed)
        // Left to the old settings, the same key is a row again.
        assertTrue(s.copy(providers = ProviderSettings()).withProviderChoices(github).ai.listed)
    }

    @Test
    fun `Brave Answers borrows the search key the user typed`() {
        val s = KeyboardSettings(
            providers = ProviderSettings(
                credentials = listOf(ProviderCredential("brave", "brave", apiKey = "search")),
                chains = mapOf(ProviderTool.AI to ProviderChain(listOf(ProviderEntry("brave_answers")), ChainMode.PICK)),
            ),
        )
        val row = s.resolveProviders(ProviderTool.AI, github, all = true).single()
        assertNull(row.problem)
        assertEquals("search", row.apiKey)
        assertEquals(AiProvider.BRAVE, s.withProviderChoices(github).ai.provider)
        val none = s.copy(providers = s.providers.copy(credentials = emptyList()))
        assertEquals(ProviderProblem.NEEDS_KEY, none.resolveProviders(ProviderTool.AI, github, all = true).single().problem)
    }

    @Test
    fun `the voice engine and the server from before the list keep dictation where it was`() {
        val s = KeyboardSettings(
            whisper = WhisperSettings(engine = "whisper", serverUrl = "http://pc:8000/v1", serverKey = "k", serverPath = "/inference"),
        )
        assertEquals(listOf("whisper", "transcription_server", "system_recognizer"), ids(s.providerChain(ProviderTool.VOICE, github)))
        val live = s.withProviderChoices(github).whisper
        assertEquals("whisper", live.engine)
        assertEquals("http://pc:8000/v1", live.serverUrl)
        assertEquals("/inference", live.serverPath)
    }

    @Test
    fun `the first voice engine that is on is the default, and an empty list still has a microphone`() {
        val chain = ProviderChain(
            listOf(
                ProviderEntry("system_recognizer", enabled = false),
                ProviderEntry("transcription_server", "transcription_server:02"),
            ),
        )
        val s = KeyboardSettings(
            providers = ProviderSettings(
                credentials = listOf(ProviderCredential("transcription_server:02", "transcription_server", url = "http://box", model = "large")),
                chains = mapOf(ProviderTool.VOICE to chain),
            ),
        )
        val live = s.withProviderChoices(github).whisper
        assertEquals("server", live.engine)
        assertEquals("http://box", live.serverUrl)
        assertEquals("large", live.serverModel)
        val empty = s.copy(providers = s.providers.copy(chains = mapOf(ProviderTool.VOICE to ProviderChain())))
        assertEquals("system", empty.withProviderChoices(github).whisper.engine)
        assertEquals(listOf("system_recognizer", "transcription_server"), ids(chain.preferringEngine("system")!!))
        assertTrue(chain.preferringEngine("system")!!.entries.first().enabled)
    }

    @Test
    fun `a voice engine this build cannot run is not offered`() {
        val lite = github.copy(voiceEngines = setOf("system", "server"))
        val offered = ProviderCatalog.addable(ProviderTool.VOICE, ProviderChain(), lite).map { it.id }
        assertEquals(listOf("system_recognizer", "transcription_server"), offered)
    }

    // ---- Music ID ----

    @Test
    fun `the music list names every service, all off until a key is typed`() {
        val chain = KeyboardSettings().providerChain(ProviderTool.MUSIC, github)
        assertTrue(on(chain).isEmpty())
        assertEquals(listOf("audd", "acrcloud", "audiotag", "rapid_shazam"), ids(chain))
        assertEquals(ChainMode.FALLBACK, chain.mode)
        // Nothing runs on the phone, so the no-internet build can add nothing.
        assertTrue(ProviderCatalog.addable(ProviderTool.MUSIC, ProviderChain(), offline).isEmpty())
    }

    @Test
    fun `ACRCloud needs its secret as well as its key, and the secret rides the credential store`() {
        val withKey = ProviderCredential("acrcloud", "acrcloud", apiKey = "k", url = "identify-eu-west-1.acrcloud.com")
        val s = KeyboardSettings(
            providers = ProviderSettings(
                credentials = listOf(withKey),
                chains = mapOf(ProviderTool.MUSIC to ProviderChain(listOf(ProviderEntry("acrcloud")))),
            ),
        )
        assertEquals(ProviderProblem.NEEDS_KEY, s.resolveProviders(ProviderTool.MUSIC, github, all = true).single().problem)
        val full = withKey.copy(extra = mapOf(CredentialSpec.SECRET to "s"))
        val ok = s.copy(providers = s.providers.copy(credentials = listOf(full)))
        assertNull(ok.resolveProviders(ProviderTool.MUSIC, github, all = true).single().problem)
        val stored = ProviderCodec.decodeCredentials(ProviderCodec.encodeCredentials(listOf(full)))!!.single()
        assertEquals("s", stored.extra[CredentialSpec.SECRET])
        assertFalse(ProviderTypes.ACRCLOUD.credential.none)
    }
}

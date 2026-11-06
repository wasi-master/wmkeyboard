package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/** How the Look up card sits over the app (#765). */
enum class LookupCardStyle {
    /** A short sheet from the bottom of the screen, the app still showing above it. */
    SHEET,

    /**
     * A small card beside the word, with the word marked, as Yomichan shows
     * it. Only a tapped word has a place on screen; a word from the text
     * selection menu still gets the sheet.
     */
    POPUP,
}

/** How a word goes to AnkiDroid from the card. */
enum class AnkiMode {
    /** Added straight to the deck when AnkiDroid allows it, else AnkiDroid's editor opens filled in. */
    AUTO,

    /** Only straight to the deck; the button asks for AnkiDroid's permission when it has none. */
    DIRECT,

    /** Always AnkiDroid's editor, filled in, to check and save there. */
    EDITOR,
}

/**
 * The Look up card (#765): a word in any app, looked up from the text
 * selection menu or by tapping it after the floating button's Look up. One
 * nested bag for the reason [GallerySettings] gives, with flat `lookup_*`
 * DataStore keys read and written by [LookupPrefs].
 */
data class LookupSettings(
    val cardStyle: LookupCardStyle = LookupCardStyle.SHEET,
    /** "Look up" in other apps' text selection menus. */
    val selectionMenu: Boolean = true,
    val ankiMode: AnkiMode = AnkiMode.AUTO,
    /** The AnkiDroid deck words go to; made on first use. */
    val ankiDeck: String = DEFAULT_ANKI_DECK,
    /** Ids of the [LookupWebSites] the Dictionary's Open in menu offers (#1150, #1161). */
    val webSites: Set<String> = emptySet(),
    /** A site of the user's own for that menu: its address with `%s` where the word goes. Blank is none. */
    val customSite: String = "",
) {
    /** The sites the Open in menu offers, built-in ones first, in the catalogue's order. */
    fun webLookups(): List<LookupWebSite> =
        LookupWebSites.ALL.filter { it.id in webSites } +
            listOfNotNull(customSite.trim().takeIf { LookupWebSites.usable(it) }?.let { LookupWebSites.custom(it) })

    companion object {
        const val DEFAULT_ANKI_DECK = "WM Keyboard"
    }
}

/** A website the Dictionary's Open in menu can look a word up on: [url] has `%s` where the word goes. */
data class LookupWebSite(val id: String, val name: String, val url: String) {
    /** The address for [word], encoded for a URL. */
    fun urlFor(word: String): String = url.replace("%s", java.net.URLEncoder.encode(word.trim(), "UTF-8").replace("+", "%20"))
}

/**
 * Dictionary sites with no API to ask, opened in the browser (or their app,
 * where the app claims the site's links) on the word (#1150, #1161). Brand
 * names, so not translated.
 */
object LookupWebSites {
    val ALL = listOf(
        LookupWebSite("takoboto", "Takoboto", "https://takoboto.jp/?q=%s"),
        LookupWebSite("shinjikai", "Shinjikai", "https://shinjikai.app/search?term=%s"),
        LookupWebSite("jisho", "Jisho", "https://jisho.org/search/%s"),
        LookupWebSite("wiktionary", "Wiktionary", "https://en.wiktionary.org/wiki/Special:Search?search=%s"),
    )

    /** An address the menu can use: http(s), with the `%s` the word goes in. */
    fun usable(url: String): Boolean =
        (url.startsWith("https://") || url.startsWith("http://")) && "%s" in url

    /** The user's own site, named by its host. */
    fun custom(url: String): LookupWebSite {
        val host = url.substringAfter("://").substringBefore('/').removePrefix("www.")
        return LookupWebSite("custom", host.ifEmpty { url }, url)
    }
}

object LookupPrefs {
    private val CARD_STYLE = stringPreferencesKey("lookup_card_style")
    private val SELECTION_MENU = booleanPreferencesKey("lookup_selection_menu")
    private val ANKI_MODE = stringPreferencesKey("lookup_anki_mode")
    private val ANKI_DECK = stringPreferencesKey("lookup_anki_deck")
    private val WEB_SITES = stringSetPreferencesKey("lookup_web_sites")
    private val CUSTOM_SITE = stringPreferencesKey("lookup_custom_site")

    private inline fun <reified E : Enum<E>> enumOf(name: String?): E? =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

    fun read(p: Preferences, d: LookupSettings): LookupSettings = LookupSettings(
        cardStyle = enumOf<LookupCardStyle>(p[CARD_STYLE]) ?: d.cardStyle,
        selectionMenu = p[SELECTION_MENU] ?: d.selectionMenu,
        ankiMode = enumOf<AnkiMode>(p[ANKI_MODE]) ?: d.ankiMode,
        ankiDeck = p[ANKI_DECK]?.trim()?.takeIf { it.isNotEmpty() } ?: d.ankiDeck,
        webSites = p[WEB_SITES] ?: d.webSites,
        customSite = p[CUSTOM_SITE] ?: d.customSite,
    )

    /** Writes only what differs from [old], so a one-field change is a one-key edit. */
    fun write(p: MutablePreferences, old: LookupSettings, new: LookupSettings) {
        if (new.cardStyle != old.cardStyle) p[CARD_STYLE] = new.cardStyle.name
        if (new.selectionMenu != old.selectionMenu) p[SELECTION_MENU] = new.selectionMenu
        if (new.ankiMode != old.ankiMode) p[ANKI_MODE] = new.ankiMode.name
        if (new.ankiDeck != old.ankiDeck) p[ANKI_DECK] = new.ankiDeck.trim()
        if (new.webSites != old.webSites) p[WEB_SITES] = new.webSites
        if (new.customSite != old.customSite) p[CUSTOM_SITE] = new.customSite.trim()
    }
}

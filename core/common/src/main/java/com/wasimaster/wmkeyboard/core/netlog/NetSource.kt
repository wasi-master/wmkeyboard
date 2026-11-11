package com.wasimaster.wmkeyboard.core.netlog

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool

/**
 * Which part of the keyboard made a network request, for the network activity
 * log.
 *
 * [id] is what the log file stores, so it is stable across renames: never store
 * [name]. The labels, the "why it happened" line and the "what was sent" note
 * live in `:app`, next to the screen that shows them.
 *
 * [tool] is the toolbar tool a source belongs to, when it belongs to one. The
 * screen draws that tool's icon in that tool's colour, so a row in the log looks
 * like the key that caused it. Sources that are not tools (downloads, backup,
 * updates) borrow the icon of the settings screen that owns them instead.
 *
 * [background] is the default for requests nobody asked for at that moment: a
 * link preview for a copied URL, refilling the photo pool, a scheduled backup.
 * A call site can override it, for the sources that happen both ways.
 */
enum class NetSource(
    val id: String,
    val tool: ToolbarTool? = null,
    val background: Boolean = false,
) {
    TRANSLATE("translate", ToolbarTool.TRANSLATE),

    /** DeepL Write, from the grammar panel or the selection bar. Its settings sit on the Translate page. */
    DEEPL_WRITE("deepl_write", ToolbarTool.TRANSLATE),

    /** A LanguageTool server the grammar tool's list asks: the public API, or one the user runs. */
    LANGUAGETOOL("languagetool", ToolbarTool.GRAMMAR),
    GIF("gif", ToolbarTool.GIF),
    STICKER("sticker", ToolbarTool.STICKER),

    /** The sticker generator's prompt sent to an image service on its list, and the pictures fetched back. */
    IMAGE_GEN("image_gen", ToolbarTool.STICKER),

    /** An emoji mix: its image, or a source's index of them. Only ever a host the user added. */
    EMOJI_MIX("emoji_mix", ToolbarTool.EMOJI_MIX),
    WEB_SEARCH("web_search", ToolbarTool.WEB_SEARCH),
    IMAGE_SEARCH("image_search", ToolbarTool.IMAGE_SEARCH),

    /** A camera photo uploaded to a reverse image search site (#349). */
    PHOTO_SEARCH("photo_search", ToolbarTool.CAMERA),
    AI("ai", ToolbarTool.AI),
    AI_CHAT("ai_chat", ToolbarTool.AI),

    /**
     * A search or a page the AI tool made on its own, because the model asked
     * for it (#470). Its own source rather than [WEB_SEARCH]: the user did not
     * run that search, and a log that said they had would be wrong about the
     * one thing it exists to record.
     */
    AI_TOOLS("ai_tools", ToolbarTool.AI),

    /**
     * A request to one of the MCP servers the user added (#573): listing its
     * tools on Connect, or a call a model made. Not [AI_TOOLS], because the
     * host is the user's choice and the request carries whatever the model
     * wrote as the tool's arguments.
     */
    AI_MCP("ai_mcp", ToolbarTool.AI),
    TRANSCRIPTION("transcription", ToolbarTool.VOICE),

    /** A Music ID clip sent to one of the recognition services on its list, each its own row. */
    MUSIC_AUDD("music_audd", ToolbarTool.MUSIC_ID),
    MUSIC_ACRCLOUD("music_acrcloud", ToolbarTool.MUSIC_ID),
    MUSIC_AUDIOTAG("music_audiotag", ToolbarTool.MUSIC_ID),
    MUSIC_RAPIDAPI("music_rapidapi", ToolbarTool.MUSIC_ID),

    /** A Music ID clip sent to the user's own recognition server. */
    MUSIC_SERVER("music_server", ToolbarTool.MUSIC_ID),

    /** Cover art and a store link for a song named without them: the iTunes Search API, then Deezer. */
    MUSIC_METADATA("music_metadata", ToolbarTool.MUSIC_ID),
    WIKIPEDIA("wikipedia", ToolbarTool.WIKIPEDIA),
    DICTIONARY("dictionary", ToolbarTool.DICTIONARY),
    VOCABULARY("vocabulary", ToolbarTool.VOCABULARY),

    /** Synonyms for a word held on the suggestion strip (#321). Its settings sit on the Suggestions page. */
    SYNONYMS("synonyms"),
    WEATHER("weather", ToolbarTool.WEATHER),

    /**
     * The address for a shared position (#350): the point at the share's
     * precision, once per share, never in the background.
     */
    LOCATION("location", ToolbarTool.LOCATION),
    CURRENCY("currency", ToolbarTool.CURRENCY),

    /**
     * The speed test's traffic: the random bytes it pulls and pushes, its
     * latency probes, and the one request that names the connection's
     * network. Only ever while a run the user started is going.
     */
    SPEED_TEST("speed_test", ToolbarTool.SPEED_TEST),
    /** Searching photos is asked for; refilling the rotation pool passes `background` itself. */
    PHOTOS("photos"),
    LINK_PREVIEW("link_preview", ToolbarTool.CLIPBOARD, background = true),

    /** Thumbnails and animations drawn by the GIF, sticker and image panels. */
    MEDIA_IMAGES("media_images"),
    DOWNLOAD_WORDLIST("download_wordlist"),
    DOWNLOAD_NGRAM("download_ngram"),
    DOWNLOAD_CJK("download_cjk"),
    DOWNLOAD_EMOJI("download_emoji", ToolbarTool.EMOJI),
    DOWNLOAD_WHISPER("download_whisper", ToolbarTool.VOICE),

    /** FUTO Swipe's glide models, when that glide engine is turned on. */
    DOWNLOAD_GLIDE("download_glide"),
    DOWNLOAD_LLM("download_llm", ToolbarTool.AI),

    /**
     * An on-device translation model: a Bergamot direction from Mozilla's
     * Firefox Translations servers, or the NLLB model from Hugging Face.
     */
    DOWNLOAD_TRANSLATE("download_translate", ToolbarTool.TRANSLATE),
    DOWNLOAD_VOCAB("download_vocab", ToolbarTool.VOCABULARY),

    /** An offline dictionary for the Dictionary tool (#665). */
    DOWNLOAD_DICTIONARY("download_dictionary", ToolbarTool.DICTIONARY),
    DOWNLOAD_FONT("download_font"),
    DOWNLOAD_CUTOUT("download_cutout", ToolbarTool.STICKER),

    /** A photo the scan text tool sends to the user's online text reader (#469). */
    OCR_ONLINE("ocr_online", ToolbarTool.OCR),

    /** Tesseract language data for the scan text tool. */
    DOWNLOAD_OCR("download_ocr", ToolbarTool.OCR),

    /** A PaddleOCR handwriting model (data repository), where ML Kit has none for the language. */
    DOWNLOAD_HANDWRITING("download_handwriting", ToolbarTool.HANDWRITING),

    /** EmbeddingGemma 2, the model behind search by meaning. */
    DOWNLOAD_SEMANTIC("download_semantic"),

    /** The sticker generator's models and picture filter (data repository, or Qualcomm's bucket for NPU builds). */
    DOWNLOAD_IMAGEGEN("download_imagegen"),

    /** The upscaler's two networks (data repository): the sticker editor's and the photo editor's Upscale. */
    DOWNLOAD_UPSCALE("download_upscale"),

    /** IndicXlit, the neural readings on the Indic phonetic layouts. */
    DOWNLOAD_XLIT("download_xlit"),

    /** Meta's MMS voices, which read text aloud offline (Read back, the speaker buttons). */
    DOWNLOAD_VOICE_OUT("download_voice_out", ToolbarTool.READ_BACK),

    /**
     * A native library a GitHub or F-Droid build fetches the first time a
     * feature needs it: the project's own from the data repository, ML Kit's,
     * LiteRT's and Qualcomm's straight from their Maven repositories.
     */
    DOWNLOAD_NATIVE("download_native"),

    /** An interface language pack the standard GitHub build fetches when its language is picked. */
    DOWNLOAD_LANGUAGE("download_language"),
    ADDONS("addons"),
    KEYMAN("keyman"),
    SIGNAL_STICKERS("signal_stickers", ToolbarTool.STICKER),
    TELEGRAM_STICKERS("telegram_stickers", ToolbarTool.STICKER),

    /** The Rboard theme list and the packs picked from it, on the Themes screen. */
    RBOARD_THEMES("rboard_themes"),
    LINK_IMPORT("link_import"),

    /** A page shared to "Save to notes", read once for its text (#997). */
    NOTES("notes", ToolbarTool.NOTES),
    BACKUP("backup"),
    UPDATES("updates", background = true),

    /** About › Supporters, when its refresh button is pressed. */
    SUPPORTERS("supporters"),
    /** A file sent or received by the Send files tool (#729): a link or a device nearby. */
    SEND("send", ToolbarTool.SEND_FILES),
    KDE_CONNECT("kde_connect", ToolbarTool.KDE_CONNECT),

    /** The user's own Home Assistant server: its socket, its REST calls, a camera picture. */
    HOME_ASSISTANT("home_assistant", ToolbarTool.HOME_ASSISTANT),

    /** The other homes of the smart home tool: Samsung's cloud, a Hue bridge on the LAN, an openHAB server, Tuya's and SwitchBot's clouds. */
    SMARTTHINGS("smartthings", ToolbarTool.HOME_ASSISTANT),
    HUE("hue", ToolbarTool.HOME_ASSISTANT),
    OPENHAB("openhab", ToolbarTool.HOME_ASSISTANT),
    TUYA("tuya", ToolbarTool.HOME_ASSISTANT),
    SWITCHBOT("switchbot", ToolbarTool.HOME_ASSISTANT),

    /**
     * The user's own Immich server, from the gallery (#1205): its timeline and
     * albums, their thumbnails, an original fetched to send, an upload.
     */
    IMMICH("immich", ToolbarTool.GALLERY),

    /**
     * The user's own Paperless-ngx server, from the document scanner (#1198):
     * its tag, correspondent and document type lists, and each scan uploaded,
     * often in the background once a network is back.
     */
    PAPERLESS("paperless", ToolbarTool.DOC_SCAN),
    OTHER("other"),
    ;

    companion object {
        fun of(id: String): NetSource = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

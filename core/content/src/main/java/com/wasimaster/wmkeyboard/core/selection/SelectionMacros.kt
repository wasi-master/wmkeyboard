package com.wasimaster.wmkeyboard.core.selection

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats
import com.wasimaster.wmkeyboard.core.util.lowercaseKeepingLength
import com.wasimaster.wmkeyboard.core.util.uppercaseKeepingLength
import java.net.URLDecoder

/**
 * What a selection turned out to be, which decides the macros offered for it.
 *
 * [TEXT] is the fallback and never an entity: it is prose, a word, a paragraph,
 * anything that is not one whole address, link or number. The three real kinds
 * are deliberately *whole-selection* readings — a sentence with a phone number
 * buried in it is [TEXT], because the user selected the sentence. Pulling the
 * fragment out of a longer span is the clipboard panel's job (see
 * `ClipEntities`), which has the room to show what it found and where.
 */
enum class SelectionKind { PHONE, EMAIL, URL, TEXT }

/**
 * One action offered for the current selection.
 *
 * The four `CASE_*` members are not offered on the bar itself: they are what
 * [SelectionMacro.FORMAT] opens on a plain-text selection, so they never appear
 * in [SelectionMacros.configurable] and have no settings switch of their own.
 */
enum class SelectionMacro {
    /**
     * Put back the last rewrite made to this selection. Pinned first on the
     * row whenever there is one; listed so it can be switched off.
     */
    UNDO,
    /**
     * Widen the selection to the whole field.
     *
     * A long press picks one word, and somebody selecting in order to copy or
     * share usually meant all of it. Left off once the whole field is
     * selected, where it has nothing left to take.
     */
    SELECT_ALL,
    COPY,
    CUT,
    /** Paste over the selection. Only offered while the clipboard holds text. */
    PASTE,
    DELETE,
    SHARE,
    /**
     * Keep the selection as a note in the Notes tool (#560): a new note, or
     * added to the last one edited, as the tool's setting says. Nothing
     * leaves the app, so it sits with the edits rather than the doors.
     */
    SAVE_NOTE,
    /**
     * Rewrite the selection in place. What that means follows the kind: a phone
     * number takes the user's own mask, a link loses its tracking parameters, an
     * address is lower-cased, and plain text opens the case ladder below.
     */
    FORMAT,
    /** Jump to the next occurrence of the selection in the field. */
    FIND,
    /** Open the Find and replace panel with the selection as the query. */
    REPLACE,
    /** Open the Text repeat tool with the selection as the text to repeat. */
    REPEAT,
    /** Open the Snippets panel over the selection: a pick replaces it, and `{selection}` reads it (#1015). */
    SNIPPET,
    LINES_SORT,
    LINES_DEDUPE,
    LINES_NUMBER,
    LINES_BULLET,
    /** A box in front of every line, a to-do list (#679); a toggle like the two above. */
    LINES_CHECKBOX,
    /** The lines the selection covers swap places with the line above them (#679). */
    LINES_UP,
    /** The same, with the line below. */
    LINES_DOWN,
    SEARCH,
    TRANSLATE,
    /** Correct the selection with the grammar checker, in place. */
    GRAMMAR_FIX,
    /**
     * Rewrite the selection with DeepL Write, in place. Only offered once the
     * user has set DeepL up with a key and switched Write on (#331).
     */
    DEEPL_WRITE,
    /** Open the AI tool on the selection. Direct buttons for single actions ride beside it. */
    AI,
    /**
     * Open the word card for a selection that is one word: the card holding
     * a suggestion-strip word opens, with Never suggest, Add, Delete, the
     * rank and the spelling. Only offered on one word; the service says
     * which selections are one ([MacroGates.oneWord]).
     */
    EDIT_WORD,
    TO_BANGLA,
    TO_BANGLISH,
    /** Romanized Hindi in the selection rewritten in Devanagari, as the Hindi phonetic layout would have typed it. */
    TO_HINDI,
    /** Devanagari in the selection written back in Latin letters. */
    TO_HINGLISH,
    /** Digits of another script rewritten as `0-9`. */
    DIGITS_LATIN,
    /**
     * Burmese between Zawgyi, the old font encoding, and Unicode: whichever
     * way the selection needs, as the detector reads it.
     */
    ZAWGYI,
    /** A colour code: a swatch on the chip, and a ladder of its other spellings. */
    COLOUR,
    JSON_FORMAT,
    BASE64_DECODE,
    URL_DECODE,
    /**
     * Take the tracking parameters off a link, in place.
     *
     * Only offered on a link that carries one, so it never shows up to do
     * nothing. Format on a link does the same and adds a missing scheme; this
     * is the chip that says what it is for, and the one that leaves a bare
     * domain bare.
     */
    STRIP_TRACKERS,
    CHAT_BOLD,
    CHAT_ITALIC,
    CHAT_STRIKE,
    CHAT_MONO,
    READ_ALOUD,
    /** The selected time shown in the user's own zones, each a chip that replaces it. */
    TIME_ZONES,
    CALL,
    SMS,
    WHATSAPP,
    /** Compose a new message to the selected address. */
    EMAIL,
    /** Hand the selection to whichever app claims it: a browser, a mail client. */
    OPEN,
    /** Turn the link into a QR code with the generator tool. */
    QR,
    ADD_CONTACT,
    MAP,
    CALENDAR,
    /**
     * Open the Fancy Text styles for the selection, and rewrite it in the one
     * picked.
     *
     * A door rather than an action, the way [FORMAT] is on plain text: a style
     * is a look, and there is no answering "which one" without showing them.
     * The ladder is drawn from `FancyStyles` rather than from members here —
     * there are thirty-odd styles, they are data, and the bar writes each chip
     * in its own style, which no enum could carry.
     */
    FANCY,
    CASE_LOWER,
    CASE_TITLE,
    CASE_UPPER,
    CASE_SENTENCE,
    /** The programmer's cases: inside the Format ladder, each with a switch of its own. */
    CASE_CAMEL,
    CASE_SNAKE,
    CASE_KEBAB,
    CASE_CONSTANT,
    ;

    /** The chip's own word, and its accessibility label. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            UNDO -> CommonR.string.common_undo
            SELECT_ALL -> CommonR.string.common_select_all
            COPY -> R.string.core_content_selection_macro_copy
            CUT -> CommonR.string.common_cut
            PASTE -> CommonR.string.common_paste
            DELETE -> CommonR.string.common_delete
            SHARE -> R.string.core_content_selection_macro_share
            SAVE_NOTE -> R.string.core_content_selection_macro_save_note
            FORMAT -> R.string.core_content_selection_macro_format
            FIND -> R.string.core_content_selection_macro_find
            REPLACE -> R.string.core_content_selection_macro_replace
            REPEAT -> R.string.core_content_selection_macro_repeat
            SNIPPET -> R.string.core_content_selection_macro_snippet
            LINES_SORT -> R.string.core_content_selection_macro_lines_sort
            LINES_DEDUPE -> R.string.core_content_selection_macro_lines_dedupe
            LINES_NUMBER -> R.string.core_content_selection_macro_lines_number
            LINES_BULLET -> R.string.core_content_selection_macro_lines_bullet
            LINES_CHECKBOX -> R.string.core_content_selection_macro_lines_checkbox
            LINES_UP -> R.string.core_content_selection_macro_lines_up
            LINES_DOWN -> R.string.core_content_selection_macro_lines_down
            SEARCH -> R.string.core_content_selection_macro_search
            TRANSLATE -> R.string.core_content_selection_macro_translate
            GRAMMAR_FIX -> R.string.core_content_selection_macro_grammar
            DEEPL_WRITE -> R.string.core_content_selection_macro_deepl_write
            AI -> R.string.core_content_selection_macro_ai
            EDIT_WORD -> R.string.core_content_selection_macro_edit_word
            TO_BANGLA -> R.string.core_content_selection_macro_to_bangla
            TO_BANGLISH -> R.string.core_content_selection_macro_to_banglish
            TO_HINDI -> R.string.core_content_selection_macro_to_hindi
            TO_HINGLISH -> R.string.core_content_selection_macro_to_hinglish
            DIGITS_LATIN -> R.string.core_content_selection_macro_digits_latin
            ZAWGYI -> R.string.core_content_selection_macro_zawgyi
            COLOUR -> R.string.core_content_selection_macro_colour
            JSON_FORMAT -> R.string.core_content_selection_macro_json
            BASE64_DECODE -> R.string.core_content_selection_macro_base64
            URL_DECODE -> R.string.core_content_selection_macro_url_decode
            STRIP_TRACKERS -> R.string.core_content_selection_macro_strip_trackers
            CHAT_BOLD -> R.string.core_content_selection_macro_chat_bold
            CHAT_ITALIC -> R.string.core_content_selection_macro_chat_italic
            CHAT_STRIKE -> R.string.core_content_selection_macro_chat_strike
            CHAT_MONO -> R.string.core_content_selection_macro_chat_mono
            READ_ALOUD -> R.string.core_content_selection_macro_read_aloud
            TIME_ZONES -> R.string.core_content_selection_macro_time_zones
            CALL -> R.string.core_content_selection_macro_call
            SMS -> R.string.core_content_selection_macro_sms
            WHATSAPP -> R.string.core_content_selection_macro_whatsapp
            EMAIL -> R.string.core_content_selection_macro_email
            OPEN -> R.string.core_content_selection_macro_open
            QR -> R.string.core_content_selection_macro_qr
            ADD_CONTACT -> R.string.core_content_selection_macro_add_contact
            MAP -> R.string.core_content_selection_macro_map
            CALENDAR -> R.string.core_content_selection_macro_calendar
            FANCY -> R.string.core_content_selection_macro_fancy
            CASE_LOWER -> R.string.core_content_selection_macro_case_lower
            CASE_TITLE -> R.string.core_content_selection_macro_case_title
            CASE_UPPER -> R.string.core_content_selection_macro_case_upper
            CASE_SENTENCE -> R.string.core_content_selection_macro_case_sentence
            CASE_CAMEL -> R.string.core_content_selection_macro_case_camel
            CASE_SNAKE -> R.string.core_content_selection_macro_case_snake
            CASE_KEBAB -> R.string.core_content_selection_macro_case_kebab
            CASE_CONSTANT -> R.string.core_content_selection_macro_case_constant
        }

    /** The group the settings screen lists the macro under. */
    val category: MacroCategory
        get() = when (this) {
            UNDO, SELECT_ALL, COPY, CUT, PASTE, DELETE, FIND, REPLACE, REPEAT, SAVE_NOTE -> MacroCategory.EDITING
            LINES_SORT, LINES_DEDUPE, LINES_NUMBER, LINES_BULLET,
            LINES_CHECKBOX, LINES_UP, LINES_DOWN,
            -> MacroCategory.LINES
            FORMAT, FANCY, CHAT_BOLD, CHAT_ITALIC, CHAT_STRIKE, CHAT_MONO,
            CASE_LOWER, CASE_TITLE, CASE_UPPER, CASE_SENTENCE,
            CASE_CAMEL, CASE_SNAKE, CASE_KEBAB, CASE_CONSTANT -> MacroCategory.FORMAT
            DIGITS_LATIN, ZAWGYI, COLOUR, JSON_FORMAT, BASE64_DECODE, URL_DECODE, STRIP_TRACKERS -> MacroCategory.CONVERT
            TRANSLATE, GRAMMAR_FIX, DEEPL_WRITE, AI, TO_BANGLA, TO_BANGLISH, TO_HINDI, TO_HINGLISH -> MacroCategory.LANGUAGE
            SEARCH, READ_ALOUD, TIME_ZONES, EDIT_WORD -> MacroCategory.LOOKUP
            SHARE, CALL, SMS, WHATSAPP, EMAIL, OPEN, QR, ADD_CONTACT, MAP, CALENDAR -> MacroCategory.OPEN_IN
        }

    /** Whether the macro leaves the keyboard for another app. */
    val leavesApp: Boolean
        get() = this == SHARE || this == CALL || this == SMS || this == WHATSAPP ||
            this == EMAIL || this == OPEN || this == ADD_CONTACT || this == MAP || this == CALENDAR

    /**
     * Whether the macro can run before the first unlock. Anything that starts
     * an activity cannot; nor can a paste (the clipboard is behind the lock),
     * reading aloud (a speech engine is another app to bind), saving a
     * note (the notes file is behind the lock too), or the word card (so is
     * the personal dictionary it edits).
     */
    val directBootSafe: Boolean
        get() = !leavesApp && this != PASTE && this != READ_ALOUD && this != SAVE_NOTE && this != SNIPPET && this != EDIT_WORD

    /** A door rather than an action: the bar opens a ladder for it and the service never sees the tap. */
    val opensLadder: Boolean
        get() = this == FORMAT || this == FANCY || this == COLOUR || this == TIME_ZONES
}

/**
 * Selection macros: a row of one-tap actions for whatever is selected.
 *
 * Selecting `01712345678` should not mean opening the dialler by hand and
 * typing it back in, and selecting a link should not mean copying it, leaving
 * the app and pasting it into a browser. The keyboard already knows what is
 * selected, so it offers the two or three things anybody would want to do with
 * that particular shape of text and leaves the rest alone.
 *
 * Everything here is pure and synchronous. [detect] reads a selection, [offer]
 * turns that reading plus the user's switches into the row, and the `format*`
 * helpers do the rewriting that [SelectionMacro.FORMAT] commits. The actions
 * themselves (an intent, a clipboard write, a tool opened) belong to the
 * keyboard service, which is the only thing that can run them.
 */
object SelectionMacros {

    /**
     * Longest selection that can still *be* an entity.
     *
     * E.164 caps a number at 15 digits, addresses are capped at 254 octets by
     * RFC 5321, and a link long enough to pass this is one nobody is going to
     * read off a chip anyway. Past it the reading is plain text without running
     * a single regex, which is what keeps a selected paragraph cheap.
     */
    private const val MAX_ENTITY_LENGTH = 320

    /**
     * Longest selection that can still be one link. Links carrying trackers
     * routinely run past [MAX_ENTITY_LENGTH] (a shop's or a newsletter's,
     * with a dozen campaign parameters), and those are the ones Remove
     * trackers is for (#665). Only a selection with no space in it is read
     * this far.
     */
    private const val MAX_URL_LENGTH = 8192

    /** Below this a selection is too short to be worth acting on at all. */
    private const val MIN_LENGTH = 1

    /**
     * A whole selection that is one address. Deliberately stricter than the
     * shapes a mail server will accept: quoted local parts and bracketed IP
     * domains exist, and a selection that looks like one is far more likely to
     * be code than an address somebody wants to write to.
     */
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]{1,64}@[A-Za-z0-9\-]{1,63}(?:\.[A-Za-z0-9\-]{1,63})*\.[A-Za-z]{2,24}""")

    /** A whole selection that is one link, with or without its scheme. */
    private val URL = Regex(
        """(?:[a-z][a-z0-9+.\-]{1,15}://|www\.)[^\s<>"'`]{2,}""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A bare domain typed without a scheme, which is how links are written in
     * ordinary prose. Kept apart from [URL] because it needs a known ending to
     * be a link at all: without one, `Mr.Smith` and `file.txt` are links.
     */
    private val BARE_DOMAIN = Regex(
        """[A-Za-z0-9\-]{1,63}(?:\.[A-Za-z0-9\-]{1,63})*\.([A-Za-z]{2,24})(?:[/?#][^\s]*)?""",
    )

    /**
     * Endings a bare domain is allowed to have.
     *
     * There are over a thousand top-level domains and several of them are also
     * ordinary file extensions (`.zip`, `.mov`, `.sh`), so a full list would
     * turn every selected filename into a link. This is the short list of
     * endings that are almost never anything else.
     */
    private val COMMON_TLDS = setOf(
        "com", "org", "net", "edu", "gov", "int", "mil", "io", "co", "dev", "app", "ai",
        "info", "biz", "me", "tv", "xyz", "online", "site", "shop", "store", "blog",
        "uk", "us", "ca", "au", "in", "bd", "pk", "de", "fr", "nl", "it", "es", "se",
        "no", "fi", "dk", "pl", "ru", "cn", "jp", "kr", "br", "mx", "za", "ng", "ke",
    )

    /**
     * A whole selection that is one phone number.
     *
     * The optional bracket after the optional `+` is for the one shape people
     * really do copy whole: `(555) 123-4567`. Anything else leading is not a
     * number, which is what keeps a bulleted line or a quoted figure out.
     */
    private val PHONE = Regex("""\+?\(?\d[\d ().\-]{5,18}\d""")

    /** Separators a number may be written with, ignored when it is reformatted. */
    private const val PHONE_SEPARATORS = " -().[]/"

    /**
     * Query parameters stripped by [stripTrackers]: campaign trackers and the
     * per-click ids the big platforms staple on. Matched case-insensitively,
     * and the campaign families by prefix, because they keep growing.
     *
     * Only names that are never about the page itself. `ref` and `source`
     * stay: plenty of sites route on them, and a cleaned link that no longer
     * opens the right page is worse than a tracked one.
     */
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "mc_eid", "mc_cid",
        "igshid", "igsh", "ttclid", "twclid", "yclid", "si", "ref_src", "ref_url",
        "_openstat", "vero_id", "vero_conv", "oly_enc_id", "oly_anon_id", "spm",
        "_hsenc", "_hsmi", "mkt_tok", "srsltid", "epik", "rb_clickid", "s_cid", "sc_cid",
        "pk_campaign", "pk_kwd", "pk_source", "pk_medium", "pk_content",
        "_ga", "_gl", "gad_source", "gad_campaignid", "gclsrc", "irclickid", "irgwc", "_kx",
        "ml_subscriber", "ml_subscriber_hash", "ss_source", "ss_campaign_id", "wickedid",
        "trk_contact", "trk_msg", "trk_module", "trk_sid", "__hssc", "__hstc", "__hsfp",
        "hsctatracking", "_branch_match_id", "_branch_referrer", "li_fat_id", "s_kwcid", "ef_id",
        "cjevent", "cjdata", "_bta_tid", "_bta_c", "sms_click", "sms_source", "sms_uph", "rtd_cid",
        "ocid", "__s",
    )

    /** Google Analytics, Matomo, Piwik, HubSpot and Oracle campaigns: a family each. */
    private val TRACKING_PREFIXES = listOf("utm_", "mtm_", "matomo_", "pk_", "hsa_", "oft_")

    /**
     * Trackers one site adds, taken only on that site (#762). Names that mean
     * something elsewhere live here and not in [TRACKING_PARAMS]: `feature`,
     * `t` and `source` are page settings on plenty of sites, and tracking
     * only on YouTube, X and Medium.
     */
    private class SiteRule(
        val domains: List<String>,
        val params: Set<String> = emptySet(),
        val prefixes: List<String> = emptyList(),
        /** Only links under this path, for a host whose other pages use the same names. */
        val path: String = "/",
        /** A tracking segment at the end of the path, taken off with the parameters. */
        val pathJunk: Regex? = null,
    ) {
        fun matches(host: String, linkPath: String): Boolean =
            domains.any { hostMatches(host, it) } && linkPath.startsWith(path)

        fun takes(name: String): Boolean = name in params || prefixes.any { name.startsWith(it) }

        fun cleanPath(url: String): String {
            val junk = pathJunk ?: return url
            val authority = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
            val end = url.indexOfAny(charArrayOf('?', '#'), authority).let { if (it < 0) url.length else it }
            val start = url.indexOf('/', authority)
            if (start < 0 || start >= end) return url
            val path = url.substring(start, end)
            val cleaned = junk.replace(path, "")
            return if (cleaned == path) url else url.substring(0, start) + cleaned + url.substring(end)
        }
    }

    private val SITE_RULES = listOf(
        SiteRule(
            listOf("amazon.", "amzn."),
            params = setOf(
                "content-id", "crid", "sprefix", "dib", "dib_tag", "qid", "sr", "ref_", "_encoding",
                "linkcode", "linkid", "camp", "creative", "creativeasin", "ascsubtag", "spla", "spia",
                "social_share", "starsleft", "skipredirect", "smid_share",
            ),
            prefixes = listOf("pd_rd_", "pf_rd_"),
            pathJunk = Regex("/ref=[^/]*$"),
        ),
        SiteRule(
            listOf("youtube.com", "youtu.be"),
            params = setOf("feature", "pp", "embeds_referring_euri", "embeds_referring_origin", "source_ve_path"),
        ),
        SiteRule(listOf("twitter.com", "x.com"), params = setOf("s", "t", "cn", "ref_src", "ref_url")),
        SiteRule(listOf("instagram.com"), params = setOf("igsh", "igshid", "ig_rid")),
        SiteRule(listOf("threads.net", "threads.com"), params = setOf("xmt", "slof")),
        SiteRule(
            listOf("facebook.com", "fb.com", "fb.watch", "messenger.com"),
            params = setOf("mibextid", "rdid", "share_url", "sfnsn", "extid", "paipv", "eav", "comment_tracking"),
            prefixes = listOf("__tn__", "__cft__", "__xts__", "__eep__"),
        ),
        SiteRule(
            listOf("tiktok.com"),
            params = setOf(
                "_r", "_t", "is_from_webapp", "sender_device", "sender_web_id", "share_app_id",
                "share_link_id", "share_item_id", "share_author_id", "tt_from", "u_code", "timestamp",
                "user_id", "social_sharing", "checksum", "sec_user_id", "enter_from", "is_copy_url",
                "web_id", "refer",
            ),
        ),
        SiteRule(
            listOf("reddit.com", "redd.it"),
            params = setOf("share_id", "rdt", "\$deep_link", "\$3p", "correlation_id", "ref_campaign", "ref_source"),
        ),
        SiteRule(listOf("spotify.com"), params = setOf("si", "nd", "pi", "dl_branch")),
        SiteRule(
            listOf("linkedin.com"),
            params = setOf(
                "trk", "trkinfo", "trackingid", "lipi", "midtoken", "midsig", "eid", "otptoken",
                "refid", "originalsubdomain", "rcm", "lici",
            ),
        ),
        SiteRule(
            listOf("aliexpress."),
            params = setOf(
                "scm", "pvid", "algo_pvid", "algo_exp_id", "btsid", "ws_ab_test", "terminal_id",
                "gatewayadapt", "_randl_currency", "_randl_shipto", "gps-id", "pdp_npi", "pdp_ext_f",
                "utparam", "sk", "af", "cv", "cn", "dp", "src", "sourcetype",
            ),
            prefixes = listOf("aff_"),
        ),
        SiteRule(
            listOf("ebay."),
            params = setOf(
                "_trkparms", "_trksid", "amdata", "hash", "mkcid", "mkevt", "mkrid", "campid", "toolid",
                "customid", "_from", "ssspo", "sssrc", "ssuid", "widget_ver", "itmmeta",
            ),
        ),
        SiteRule(
            listOf("etsy.com"),
            params = setOf(
                "click_key", "click_sum", "ref", "pro", "frs", "sts", "organic_search_click", "plkey",
                "external", "sr_prefetch", "pf_from", "sca", "bes", "content_source", "pop",
            ),
            prefixes = listOf("ga_"),
        ),
        SiteRule(listOf("medium.com"), params = setOf("source")),
        SiteRule(listOf("pinterest.", "pin.it"), params = setOf("invite_code", "sender", "sfo")),
        SiteRule(
            listOf("google."),
            params = setOf(
                "ved", "ei", "sca_esv", "sca_upv", "oq", "gs_lcrp", "gs_lp", "gs_lcp", "gs_ssp", "sourceid",
                "sxsrf", "iflsig", "sclient", "rlz", "bih", "biw", "dpr", "uact", "aqs", "client", "stick",
                "sei", "mstk", "csui", "prmd", "fbs", "vet", "sa", "source", "ie",
            ),
            path = "/search",
        ),
        SiteRule(
            listOf("bing.com"),
            params = setOf("form", "cvid", "pq", "sk", "sc", "qs", "sp", "ghc", "lq", "ghsh", "ghacc", "ghpl"),
            path = "/search",
        ),
    )

    /**
     * Links that only send the browser on somewhere else, and the parameter
     * that holds where (#762). Unwrapped only when that parameter is a whole
     * `http(s)` link, so a wrapper that encodes its target is left alone.
     */
    private class Redirect(val domain: String, val path: String, val params: Set<String>)

    private val REDIRECTS = listOf(
        Redirect("google.", "/url", setOf("q", "url")),
        Redirect("l.facebook.com", "/", setOf("u")),
        Redirect("lm.facebook.com", "/", setOf("u")),
        Redirect("l.messenger.com", "/", setOf("u")),
        Redirect("l.instagram.com", "/", setOf("u")),
        Redirect("l.threads.net", "/", setOf("u")),
        Redirect("l.threads.com", "/", setOf("u")),
        Redirect("youtube.com", "/redirect", setOf("q")),
        Redirect("out.reddit.com", "/", setOf("url")),
        Redirect("steamcommunity.com", "/linkfilter", setOf("url", "u")),
        Redirect("safelinks.protection.outlook.com", "/", setOf("url")),
        Redirect("slack-redir.net", "/link", setOf("url")),
        Redirect("disq.us", "/url", setOf("url")),
        Redirect("vk.com", "/away.php", setOf("to")),
        Redirect("t.umblr.com", "/redirect", setOf("z")),
        Redirect("duckduckgo.com", "/l/", setOf("uddg")),
        Redirect("linkedin.com", "/redir/redirect", setOf("url")),
        Redirect("click.linksynergy.com", "/", setOf("murl")),
        Redirect("go.redirectingat.com", "/", setOf("url")),
        Redirect("exit.sc", "/", setOf("url")),
    )

    /** Wrappers wrapped in wrappers, unwrapped this many deep at most. */
    private const val MAX_REDIRECT_DEPTH = 3

    /** The four fixed cases: never configurable, always inside the Format ladder. */
    val fixedCaseMacros: List<SelectionMacro> = listOf(
        SelectionMacro.CASE_LOWER,
        SelectionMacro.CASE_TITLE,
        SelectionMacro.CASE_UPPER,
        SelectionMacro.CASE_SENTENCE,
    )

    /** Configurable, but never on the row: they live inside the Format ladder. */
    val ladderOnly: Set<SelectionMacro> = setOf(
        SelectionMacro.CASE_CAMEL,
        SelectionMacro.CASE_SNAKE,
        SelectionMacro.CASE_KEBAB,
        SelectionMacro.CASE_CONSTANT,
    )

    /** The macros a settings screen can switch. */
    val configurable: List<SelectionMacro> = SelectionMacro.entries - fixedCaseMacros.toSet()

    /**
     * The actions that need neither the selected text nor a scan of it: they
     * go through the editor's own clipboard and selection, so they cost the
     * same however much is selected.
     *
     * This is what the bar can still offer past the length cap, where reading
     * the selection is the expense the cap exists to avoid (#525). Everything
     * else either rewrites the selection or acts on what it says, and both
     * start by reading it.
     */
    val textFreeMacros: Set<SelectionMacro> = setOf(
        SelectionMacro.SELECT_ALL,
        SelectionMacro.COPY,
        SelectionMacro.CUT,
        SelectionMacro.PASTE,
        SelectionMacro.DELETE,
    )

    /**
     * What the row offers a bare caret, for the always-shown row (#763): the
     * two actions that mean something with nothing selected. Select all is
     * the way to the rest of the bar.
     */
    val caretMacros: Set<SelectionMacro> = setOf(
        SelectionMacro.SELECT_ALL,
        SelectionMacro.PASTE,
    )

    /**
     * The row as shipped. Only bar-capable macros, in the order they read
     * when the user has not moved anything: Undo, Select all, then what the
     * entity is for (absent on plain text, so they cost it nothing), then the
     * generic edits and everything content-gated after them.
     */
    val defaultOrder: List<SelectionMacro> = listOf(
        SelectionMacro.UNDO, SelectionMacro.SELECT_ALL,
        SelectionMacro.CALL, SelectionMacro.SMS, SelectionMacro.WHATSAPP, SelectionMacro.EMAIL,
        SelectionMacro.OPEN, SelectionMacro.STRIP_TRACKERS, SelectionMacro.QR, SelectionMacro.ADD_CONTACT,
        SelectionMacro.COPY, SelectionMacro.CUT, SelectionMacro.PASTE, SelectionMacro.DELETE, SelectionMacro.SHARE,
        SelectionMacro.SAVE_NOTE, SelectionMacro.EDIT_WORD,
        SelectionMacro.FORMAT, SelectionMacro.FIND, SelectionMacro.REPLACE, SelectionMacro.REPEAT, SelectionMacro.SNIPPET,
        SelectionMacro.LINES_SORT, SelectionMacro.LINES_DEDUPE, SelectionMacro.LINES_NUMBER, SelectionMacro.LINES_BULLET,
        SelectionMacro.LINES_CHECKBOX, SelectionMacro.LINES_UP, SelectionMacro.LINES_DOWN,
        SelectionMacro.GRAMMAR_FIX, SelectionMacro.DEEPL_WRITE, SelectionMacro.TRANSLATE, SelectionMacro.AI,
        SelectionMacro.TO_BANGLA, SelectionMacro.TO_BANGLISH,
        SelectionMacro.TO_HINDI, SelectionMacro.TO_HINGLISH,
        SelectionMacro.DIGITS_LATIN, SelectionMacro.ZAWGYI, SelectionMacro.COLOUR, SelectionMacro.FANCY,
        SelectionMacro.CHAT_BOLD, SelectionMacro.CHAT_ITALIC, SelectionMacro.CHAT_STRIKE, SelectionMacro.CHAT_MONO,
        SelectionMacro.JSON_FORMAT, SelectionMacro.BASE64_DECODE, SelectionMacro.URL_DECODE,
        SelectionMacro.TIME_ZONES, SelectionMacro.CALENDAR, SelectionMacro.MAP, SelectionMacro.READ_ALOUD,
        SelectionMacro.SEARCH,
    )

    /**
     * The shipped set. Search is off because it is a round trip to a network
     * service that spends a small daily quota; DeepL Write is one too, but it
     * is on because its gate already waits for the user to set DeepL up and
     * switch Write on. Translate is on (#434): it only opens the Translate
     * tool, which may translate on the device, and a selection in a language
     * the user cannot read is the one they could not have typed into the
     * tool by hand. The programmer's cases, chat markup, speech, maps,
     * calendars and the decoders are off because each is a taste rather than
     * a need, and the row is long enough already.
     */
    val defaultMacros: Set<SelectionMacro> = setOf(
        SelectionMacro.UNDO, SelectionMacro.SELECT_ALL, SelectionMacro.COPY, SelectionMacro.CUT,
        SelectionMacro.PASTE, SelectionMacro.DELETE, SelectionMacro.SHARE, SelectionMacro.SAVE_NOTE, SelectionMacro.FORMAT,
        SelectionMacro.FIND, SelectionMacro.REPLACE, SelectionMacro.REPEAT,
        SelectionMacro.LINES_SORT, SelectionMacro.LINES_DEDUPE, SelectionMacro.LINES_NUMBER, SelectionMacro.LINES_BULLET,
        SelectionMacro.GRAMMAR_FIX, SelectionMacro.DEEPL_WRITE, SelectionMacro.TRANSLATE, SelectionMacro.AI,
        SelectionMacro.TO_BANGLA, SelectionMacro.TO_BANGLISH,
        SelectionMacro.TO_HINDI, SelectionMacro.TO_HINGLISH,
        SelectionMacro.DIGITS_LATIN, SelectionMacro.ZAWGYI, SelectionMacro.COLOUR,
        SelectionMacro.CALL, SelectionMacro.SMS, SelectionMacro.WHATSAPP, SelectionMacro.EMAIL,
        SelectionMacro.OPEN, SelectionMacro.QR, SelectionMacro.ADD_CONTACT, SelectionMacro.FANCY,
        SelectionMacro.STRIP_TRACKERS, SelectionMacro.EDIT_WORD,
    )

    /**
     * Bumped when [defaultMacros] or [defaultOrder] change in a way every
     * existing user should receive. A stored list under an older version is
     * read as unset, which is the shipped list.
     */
    const val LIST_VERSION = 4

    /** The case ladder [SelectionMacro.FORMAT] opens on a plain-text selection. */
    fun caseLadder(allowed: Set<SelectionMacro>): List<SelectionMacro> =
        fixedCaseMacros + ladderOnly.filter { it in allowed }

    /**
     * What [selection] is, as a whole.
     *
     * [phoneFormats] are the user's own masks, as they are stored (see
     * `PhoneFormats`). When the list is not empty a number has to match one of
     * them, which is what stops an order total or a row of figures from
     * offering to dial itself. With no masks set the shape rules stand alone,
     * as they do in the clipboard panel.
     */
    fun detect(selection: String, phoneFormats: List<String> = emptyList()): SelectionKind {
        val trimmed = selection.trim()
        if (trimmed.length < MIN_LENGTH) return SelectionKind.TEXT
        if (trimmed.length > MAX_ENTITY_LENGTH) {
            // Too long for an address or a number, but not for a link.
            val oneLink = trimmed.length <= MAX_URL_LENGTH && trimmed.none { it.isWhitespace() } && isUrl(trimmed)
            return if (oneLink) SelectionKind.URL else SelectionKind.TEXT
        }
        // A line break means the user swept up more than one thing, whatever
        // the pieces look like on their own.
        if (trimmed.any { it == '\n' || it == '\r' }) return SelectionKind.TEXT
        if (EMAIL.matches(trimmed)) return SelectionKind.EMAIL
        if (isUrl(trimmed)) return SelectionKind.URL
        if (isPhone(trimmed, phoneFormats)) return SelectionKind.PHONE
        return SelectionKind.TEXT
    }

    private fun isUrl(trimmed: String): Boolean {
        // A link with a scheme says so itself and needs no ending check.
        if (URL.matches(trimmed)) return true
        val bare = BARE_DOMAIN.matchEntire(trimmed) ?: return false
        return bare.groupValues[1].lowercase() in COMMON_TLDS
    }

    private fun isPhone(trimmed: String, phoneFormats: List<String>): Boolean {
        if (!PHONE.matches(trimmed)) return false
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length !in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS) return false
        // Punctuation that never appears in a phone number rules one out even
        // where the digit run would have passed.
        if (trimmed.any { !it.isDigit() && it != '+' && it !in PHONE_SEPARATORS }) return false
        return PhoneFormats.matches(trimmed, PhoneFormats.parseAll(phoneFormats))
    }

    private const val MIN_PHONE_DIGITS = 7
    private const val MAX_PHONE_DIGITS = 15

    /**
     * The row for [kind]: the user's [order] (anything it does not name comes
     * after it in shipped order, so a new macro appears instead of vanishing)
     * filtered to what [allowed] turns on, what the kind can carry and what
     * [gates] say the device and the text allow. Undo, when there is one, is
     * first whatever the order says.
     */
    fun offer(
        kind: SelectionKind,
        allowed: Set<SelectionMacro>,
        gates: MacroGates = MacroGates(),
        order: List<SelectionMacro> = defaultOrder,
    ): List<SelectionMacro> {
        val sequence = order.filter { it !in ladderOnly } + defaultOrder.filter { it !in order }
        val row = sequence.filter { it in allowed && eligible(it, kind) && passes(it, gates) }
        return if (SelectionMacro.UNDO in row) listOf(SelectionMacro.UNDO) + (row - SelectionMacro.UNDO) else row
    }

    /** Every macro [kind] can carry, in shipped order: the eligibility, not the gates. */
    fun macrosFor(kind: SelectionKind): List<SelectionMacro> = defaultOrder.filter { eligible(it, kind) }

    /**
     * Which kinds a macro belongs to. The entity actions follow their entity;
     * the edits and the doors follow every selection; everything else is for
     * prose, where a styled link is not a link and a numbered phone number is
     * nonsense.
     */
    private fun eligible(macro: SelectionMacro, kind: SelectionKind): Boolean = when (macro) {
        SelectionMacro.CALL, SelectionMacro.SMS, SelectionMacro.WHATSAPP -> kind == SelectionKind.PHONE
        SelectionMacro.EMAIL -> kind == SelectionKind.EMAIL
        SelectionMacro.QR -> kind == SelectionKind.URL
        // Text too: a link inside a sentence is cleaned where it stands (#665).
        SelectionMacro.STRIP_TRACKERS -> kind == SelectionKind.URL || kind == SelectionKind.TEXT
        SelectionMacro.OPEN -> kind == SelectionKind.URL || kind == SelectionKind.EMAIL
        SelectionMacro.ADD_CONTACT -> kind == SelectionKind.PHONE || kind == SelectionKind.EMAIL
        SelectionMacro.URL_DECODE -> kind == SelectionKind.URL || kind == SelectionKind.TEXT
        SelectionMacro.UNDO, SelectionMacro.SELECT_ALL, SelectionMacro.COPY, SelectionMacro.CUT,
        SelectionMacro.PASTE, SelectionMacro.DELETE, SelectionMacro.SHARE, SelectionMacro.SAVE_NOTE, SelectionMacro.FORMAT,
        SelectionMacro.FIND, SelectionMacro.REPLACE, SelectionMacro.READ_ALOUD, SelectionMacro.SNIPPET -> true
        else -> kind == SelectionKind.TEXT
    }

    private fun passes(macro: SelectionMacro, gates: MacroGates): Boolean = when (macro) {
        SelectionMacro.WHATSAPP -> gates.whatsAppInstalled
        SelectionMacro.QR -> gates.qrAvailable
        SelectionMacro.FORMAT -> gates.formattable
        SelectionMacro.SELECT_ALL -> !gates.wholeField
        // A whole-field or multi-line selection has no "next" worth jumping to.
        SelectionMacro.FIND -> !gates.wholeField && !gates.content.multiLine
        SelectionMacro.PASTE -> gates.clipboardHasText
        SelectionMacro.UNDO -> gates.undoAvailable
        SelectionMacro.LINES_SORT, SelectionMacro.LINES_DEDUPE,
        SelectionMacro.LINES_NUMBER, SelectionMacro.LINES_BULLET,
        SelectionMacro.LINES_CHECKBOX,
        -> gates.content.multiLine
        // One line moves as well as several; there is nowhere to move the
        // whole text to.
        SelectionMacro.LINES_UP, SelectionMacro.LINES_DOWN -> !gates.wholeField
        SelectionMacro.GRAMMAR_FIX -> gates.grammarAvailable
        SelectionMacro.DEEPL_WRITE -> gates.deeplWriteAvailable
        SelectionMacro.AI -> gates.aiAvailable
        SelectionMacro.TO_BANGLA -> gates.bengaliLoaded && gates.content.hasLatin && !gates.content.hasBengali
        SelectionMacro.TO_BANGLISH -> gates.content.hasBengali
        // Both ways need Hindi to be a language this keyboard is typing: Devanagari
        // is a dozen languages' script, and a Marathi selection is not asking to
        // be read as Hindi by someone who never enabled it.
        SelectionMacro.TO_HINDI -> gates.hindiLoaded && gates.content.hasLatin && !gates.content.hasDevanagari
        SelectionMacro.TO_HINGLISH -> gates.hindiLoaded && gates.content.hasDevanagari
        SelectionMacro.DIGITS_LATIN -> gates.content.hasForeignDigits
        SelectionMacro.ZAWGYI -> gates.content.hasMyanmar
        SelectionMacro.COLOUR -> gates.content.colour != null
        SelectionMacro.MAP -> gates.content.place != null
        SelectionMacro.CALENDAR -> gates.content.dateTime != null
        SelectionMacro.TIME_ZONES -> gates.content.dateTime?.hasTime == true
        SelectionMacro.CHAT_BOLD, SelectionMacro.CHAT_ITALIC, SelectionMacro.CHAT_STRIKE -> gates.chatSyntax != null
        SelectionMacro.CHAT_MONO -> gates.chatSyntax?.mono != null
        SelectionMacro.JSON_FORMAT -> gates.content.jsonShape != JsonReformat.Shape.NONE
        SelectionMacro.BASE64_DECODE -> gates.content.base64
        SelectionMacro.URL_DECODE -> gates.content.urlEncoded
        SelectionMacro.STRIP_TRACKERS -> gates.content.hasTrackers
        SelectionMacro.READ_ALOUD -> gates.ttsAvailable
        SelectionMacro.EDIT_WORD -> gates.oneWord
        else -> true
    }

    /**
     * The selection rewritten for its kind, or null when there is nothing to
     * do. Plain text is not handled here: its Format opens the case ladder
     * instead, and [applyCase] is what commits one of those.
     */
    fun format(selection: String, kind: SelectionKind, phoneFormats: List<String> = emptyList()): String? {
        val trimmed = selection.trim()
        val formatted = when (kind) {
            SelectionKind.PHONE -> formatPhone(trimmed, phoneFormats)
            SelectionKind.EMAIL -> trimmed.lowercase()
            SelectionKind.URL -> formatUrl(trimmed)
            SelectionKind.TEXT -> null
        } ?: return null
        return formatted.takeIf { it != trimmed }
    }

    /**
     * [number] written the way the user's own mask writes it.
     *
     * The mask is rendered rather than described: its separators are copied
     * across and its digit slots filled, so the result is the shape the user
     * typed into the formats list and not a shape this file invented. A mask
     * that names a country code adds it back when the number was written
     * without one, which is the whole point of the action for anybody who
     * stores numbers internationally and copies them nationally.
     *
     * With no matching mask the fallback is E.164 when the number already
     * declares a country code, and the bare digit run otherwise. Both are
     * tidier than a number carrying whatever spacing it was pasted with.
     */
    fun formatPhone(number: String, phoneFormats: List<String>): String? {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val explicit = number.trimStart().startsWith("+")
        val raw = phoneFormats.firstOrNull { mask ->
            PhoneFormats.parse(mask)?.let { PhoneFormats.matches(number, listOf(it)) } == true
        } ?: return if (explicit) "+$digits" else digits
        val mask = PhoneFormats.parse(raw) ?: return null
        val national = digits.takeLast(mask.national.length)
        if (national.length < mask.national.length) return null
        return renderMask(raw, mask.countryCode.orEmpty() + national)
    }

    /**
     * Fills [mask]'s digit slots with [digits], copying everything else across.
     *
     * A slot is a digit or an [PhoneFormats.ANY] in the mask; a separator, a
     * bracket or the leading `+` is decoration and travels unchanged. Null when
     * the two do not line up, which the caller has already ruled out but which
     * a mask edited between the match and the render could still cause.
     */
    private fun renderMask(mask: String, digits: String): String? {
        val out = StringBuilder(mask.length)
        var next = 0
        for (ch in mask.trim()) {
            if (ch.isDigit() || ch == PhoneFormats.ANY || ch in MASK_WILDCARDS) {
                if (next >= digits.length) return null
                out.append(digits[next])
                next++
            } else {
                out.append(ch)
            }
        }
        return if (next == digits.length) out.toString() else null
    }

    /** How a hand-written mask can spell "any digit"; canonical form is `X`. */
    private const val MASK_WILDCARDS = "x#"

    /** [url] with its tracking parameters removed, and a scheme added when it had none. */
    fun formatUrl(url: String): String? {
        val withScheme = if (url.contains("://")) url else "https://$url"
        return stripTrackers(withScheme) ?: withScheme
    }

    /**
     * [url] without its tracking parameters, or null when it carries none.
     *
     * Three things, all of them provably not about which page it is (#762):
     * a redirect wrapper (Google's `/url?q=`, Facebook's `l.php?u=`, an
     * Outlook safe link) is unwrapped to the link inside it; the query loses
     * the global trackers plus the ones a known site adds ([SITE_RULES]); and
     * on the few sites that keep their tracking in the path (Amazon's
     * `/ref=…`) that segment goes. Anything else in a path, the fragment and
     * the host stay, because rewriting those is how a "clean this link"
     * feature quietly breaks the link. Nor is a scheme added: the link keeps
     * the shape it was written in.
     */
    fun stripTrackers(url: String, extra: Collection<String> = emptyList()): String? {
        val trimmed = url.trim()
        var link = trimmed
        // A wrapper can wrap another (an Outlook safe link round a Google
        // one), so unwrap until the link stands on its own, a few times at most.
        for (i in 0 until MAX_REDIRECT_DEPTH) link = unwrapRedirect(link) ?: break
        val host = hostOf(link)
        val path = pathOf(link)
        val sites = SITE_RULES.filter { it.matches(host, path) }
        link = stripQuery(link, extra, sites) ?: link
        for (site in sites) link = site.cleanPath(link)
        return link.takeIf { it != trimmed }
    }

    /** [url] without the query parameters [isTracker] names, or null when there are none. */
    private fun stripQuery(url: String, extra: Collection<String>, sites: List<SiteRule>): String? {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return null
        // A `?` inside the fragment is not a query.
        val hash = url.indexOf('#')
        if (hash in 0 until queryStart) return null
        val fragmentStart = url.indexOf('#', queryStart)
        val query = if (fragmentStart < 0) {
            url.substring(queryStart + 1)
        } else {
            url.substring(queryStart + 1, fragmentStart)
        }
        val fragment = if (fragmentStart < 0) "" else url.substring(fragmentStart)
        val parts = query.split('&')
        val kept = parts.filter { part ->
            val name = part.substringBefore('=').lowercase()
            name.isNotEmpty() && !isTracker(name, extra, sites)
        }
        // Empty pieces (`a=1&&b=2`, a trailing `?`) go too, but on their own
        // they are not trackers and do not make a link worth offering to clean.
        if (kept.size == parts.count { it.substringBefore('=').isNotEmpty() }) return null
        val base = url.substring(0, queryStart)
        return if (kept.isEmpty()) base + fragment else "$base?${kept.joinToString("&")}$fragment"
    }

    /**
     * The link [url] wraps, when it is one of the [REDIRECTS] and the wrapped
     * value really is an `http(s)` link; null otherwise.
     */
    private fun unwrapRedirect(url: String): String? {
        val host = hostOf(url)
        val path = pathOf(url)
        val rule = REDIRECTS.firstOrNull { hostMatches(host, it.domain) && path.startsWith(it.path) } ?: return null
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            if (part.substringBefore('=').lowercase() !in rule.params) continue
            val target = runCatching {
                URLDecoder.decode(part.substringAfter('=', ""), "UTF-8").trim()
            }.getOrNull() ?: continue
            val isLink = target.startsWith("https://", ignoreCase = true) ||
                target.startsWith("http://", ignoreCase = true)
            if (isLink && target.none { it.isWhitespace() }) return target
        }
        return null
    }

    /** [url]'s host, lower case and without a leading `www.`; the link need not have a scheme. */
    private fun hostOf(url: String): String {
        val authority = url.substringAfter("://", url).takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@').substringBefore(':').lowercase().removePrefix("www.")
    }

    /** [url]'s path, `/` when it has none. */
    private fun pathOf(url: String): String {
        val rest = url.substringAfter("://", url)
        val start = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (start < 0 || rest[start] != '/') return "/"
        return rest.substring(start).takeWhile { it != '?' && it != '#' }
    }

    /**
     * Whether [host] is [domain] or under it. A domain ending in `.` is the
     * brand under any country's ending: `amazon.` takes amazon.de,
     * amazon.co.uk and smile.amazon.com.
     */
    private fun hostMatches(host: String, domain: String): Boolean = if (domain.endsWith('.')) {
        host.startsWith(domain) || host.contains(".$domain")
    } else {
        host == domain || host.endsWith(".$domain")
    }

    /**
     * Whether the query parameter [name] (lower case) is a tracker: one of the
     * built-in names or families, one a matching site rule names, or one of
     * the user's own [extra] names (#665). An extra name ending in `*` is a
     * family: `abc_*` takes every parameter starting `abc_`.
     */
    private fun isTracker(name: String, extra: Collection<String>, sites: List<SiteRule>): Boolean {
        if (TRACKING_PREFIXES.any { name.startsWith(it) } || name in TRACKING_PARAMS) return true
        if (sites.any { it.takes(name) }) return true
        for (raw in extra) {
            val entry = raw.trim().lowercase()
            if (entry.isEmpty()) continue
            if (entry.endsWith('*')) {
                if (entry.length > 1 && name.startsWith(entry.dropLast(1))) return true
            } else if (name == entry) {
                return true
            }
        }
        return false
    }

    /**
     * [text] with the trackers taken off every link in it, or null when no
     * link carried any (#665). A link is a run without spaces that reads as
     * one ([isUrl]); punctuation a sentence closes it with stays outside it.
     * Everything else in the text is left exactly as it was.
     */
    fun stripTrackersInText(text: String, extra: Collection<String> = emptyList()): String? {
        if (!mayCarryTrackers(text)) return null
        var changed = false
        val out = NON_SPACE_RUN.replace(text) { match ->
            val run = match.value
            if (!mayCarryTrackers(run)) return@replace run
            val core = run.trimEnd { it in LINK_TRAILING_PUNCTUATION }
            val tail = run.substring(core.length)
            if (core.length > MAX_URL_LENGTH || !isUrl(core)) return@replace run
            val clean = stripTrackers(core, extra) ?: return@replace run
            changed = true
            clean + tail
        }
        return out.takeIf { changed }
    }

    /**
     * The cheap test before the real one: every tracker [stripTrackers] takes
     * sits in a query, except the path segment of [SITE_RULES] like Amazon's.
     */
    private fun mayCarryTrackers(text: String): Boolean = text.contains('?') || text.contains("/ref=")

    private val NON_SPACE_RUN = Regex("""\S+""")

    /** What a sentence ends a link with, which is not part of the link. */
    private const val LINK_TRAILING_PUNCTUATION = ".,;:!)]}'\"»”’>"

    /**
     * [selection] in the case [macro] names, or null when it is already there.
     *
     * Title case capitalises every word and sentence case only the first, which
     * is the distinction people actually reach for. Neither touches a word that
     * is already all capitals: an acronym in the middle of a sentence is not a
     * casing mistake, and lower-casing it is the one edit that cannot be undone
     * by another tap on this same row.
     *
     * That rule is suspended when the *whole* selection is capitals, which is
     * the case somebody selecting SHOUTED TEXT and reaching for Title case
     * actually has. Reading every word there as an acronym would leave the
     * selection exactly as it was and make both chips look broken.
     */
    fun applyCase(selection: String, macro: SelectionMacro): String? {
        // Nothing lower-cased anywhere, and at least one letter to judge by.
        val allCaps = selection.any { it.isLetter() } && selection.none { it.isLowerCase() }
        val result = when (macro) {
            // Spelling-keeping forms, so ß goes to ẞ and back rather than to SS (#715).
            SelectionMacro.CASE_LOWER -> selection.lowercaseKeepingLength()
            SelectionMacro.CASE_UPPER -> selection.uppercaseKeepingLength()
            SelectionMacro.CASE_TITLE -> recase(selection, everyWord = true, keepAcronyms = !allCaps)
            SelectionMacro.CASE_SENTENCE -> recase(selection, everyWord = false, keepAcronyms = !allCaps)
            SelectionMacro.CASE_CAMEL -> CodeCases.camel(selection) ?: return null
            SelectionMacro.CASE_SNAKE -> CodeCases.snake(selection) ?: return null
            SelectionMacro.CASE_KEBAB -> CodeCases.kebab(selection) ?: return null
            SelectionMacro.CASE_CONSTANT -> CodeCases.constant(selection) ?: return null
            else -> return null
        }
        return result.takeIf { it != selection }
    }

    /**
     * The one walk behind Title case and Sentence case.
     *
     * [everyWord] capitalises each word rather than only the first of each
     * sentence; [keepAcronyms] leaves an all-capitals word alone. Words are
     * runs of letters plus the apostrophes inside them, so "don't" is one word
     * and its `t` is not a fresh one to capitalise.
     */
    private fun recase(text: String, everyWord: Boolean, keepAcronyms: Boolean): String {
        val out = StringBuilder(text.length)
        var startOfSentence = true
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (!ch.isLetter()) {
                out.append(ch)
                if (ch in SENTENCE_ENDS) startOfSentence = true
                index++
                continue
            }
            var end = index
            while (end < text.length && (text[end].isLetter() || text[end] in WORD_INNER)) end++
            // An apostrophe is only inside a word while a letter follows it.
            while (end > index && text[end - 1] in WORD_INNER) end--
            val word = text.substring(index, end)
            val acronym = keepAcronyms && word.none { it.isLowerCase() }
            out.append(
                when {
                    acronym -> word
                    everyWord || startOfSentence -> word[0].uppercaseChar() + word.substring(1).lowercaseKeepingLength()
                    else -> word.lowercaseKeepingLength()
                },
            )
            startOfSentence = false
            index = end
        }
        return out.toString()
    }

    /** Characters that sit inside a word without ending it. */
    private const val WORD_INNER = "'’"

    private const val SENTENCE_ENDS = ".!?।؟"

    /**
     * The number to dial, as `tel:` and `wa.me` want it: digits only, with the
     * dial code when the selection or a mask supplies one.
     */
    fun dialDigits(number: String, phoneFormats: List<String>): String {
        val digits = number.filter { it.isDigit() }
        if (number.trimStart().startsWith("+")) return digits
        val mask = PhoneFormats.parseAll(phoneFormats)
            .firstOrNull { PhoneFormats.matches(number, listOf(it)) }
        val code = mask?.countryCode ?: return digits
        return code + digits.takeLast(mask.national.length)
    }

    /** The link as something a browser will take: a bare domain gains `https://`. */
    fun openableUrl(url: String): String =
        if (url.contains("://")) url else "https://${url.removePrefix("//")}"

    /**
     * What [text] holds, for the content-gated macros.
     *
     * Runs on every selection change over up to 4,000 characters, so the
     * order is cost: one pass over the characters answers the script and
     * line questions, and every detector after it is behind a cheap shape
     * check (a colour starts with `#`, JSON with a brace) or behind
     * [options], which only asks for dates and places while a macro that
     * needs them is switched on.
     */
    fun detectContent(text: String, options: DetectOptions = DetectOptions()): ContentFlags {
        var hasLatin = false
        var hasBengali = false
        var hasDevanagari = false
        var hasMyanmar = false
        var hasForeignDigits = false
        var hasDigit = false
        var lines = 0
        var lineHasContent = false
        for (c in text) {
            when {
                c == '\n' -> {
                    if (lineHasContent) lines++
                    lineHasContent = false
                }
                c.isWhitespace() -> {}
                else -> {
                    lineHasContent = true
                    when {
                        c in 'a'..'z' || c in 'A'..'Z' -> hasLatin = true
                        c in '0'..'9' -> hasDigit = true
                        c.code in 0x0900..0x0963 || c.code in 0x0970..0x097F -> hasDevanagari = true
                        // The Myanmar block without its digits, which the digit
                        // branch below reads.
                        c.code in 0x1000..0x103F || c.code in 0x104A..0x109F -> hasMyanmar = true
                        c.code in 0x0980..0x09FF -> {
                            hasBengali = true
                            if (c in '০'..'৯') {
                                hasForeignDigits = true
                                hasDigit = true
                            }
                        }
                        !hasForeignDigits && DigitScripts.digitValue(c) >= 0 -> {
                            hasForeignDigits = true
                            hasDigit = true
                        }
                    }
                }
            }
        }
        if (lineHasContent) lines++
        val trimmed = text.trim()
        val single = lines <= 1 && !trimmed.contains('\n')
        val colour = if (single && trimmed.length <= ColourCodes.MAX_LENGTH) ColourCodes.parse(trimmed) else null
        val jsonShape = if (JsonReformat.looksStructured(trimmed)) JsonReformat.shape(trimmed) else JsonReformat.Shape.NONE
        val base64 = single && trimmed.length >= 8 && TextCodecs.looksBase64(trimmed)
        val urlEncoded = TextCodecs.isUrlEncoded(trimmed)
        val hasTrackers = mayCarryTrackers(trimmed) && stripTrackersInText(trimmed, options.extraTrackers) != null
        val couldBeMoment = single && trimmed.length <= DateTimes.MAX_LENGTH &&
            (hasDigit || trimmed.firstOrNull()?.isLetter() == true)
        val dateTime = if (options.dateTime && couldBeMoment) {
            DateTimes.parse(trimmed, options.nowMillis, options.zone, options.locale)
        } else {
            null
        }
        val couldBePlace = single && trimmed.length <= Places.MAX_ADDRESS_LENGTH && hasDigit
        val place = if (options.place && couldBePlace) {
            Places.detect(trimmed) { candidate ->
                dateTime != null ||
                    (!options.dateTime && DateTimes.parse(candidate, options.nowMillis, options.zone, options.locale) != null)
            }
        } else {
            null
        }
        return ContentFlags(
            multiLine = lines >= 2,
            hasLatin = hasLatin,
            hasBengali = hasBengali,
            hasDevanagari = hasDevanagari,
            hasMyanmar = hasMyanmar,
            hasForeignDigits = hasForeignDigits,
            colour = colour,
            dateTime = dateTime,
            place = place,
            jsonShape = jsonShape,
            base64 = base64,
            urlEncoded = urlEncoded,
            hasTrackers = hasTrackers,
        )
    }
}

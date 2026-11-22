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
    /** Korean text with its word spacing corrected, once its download is on the phone. */
    KOREAN_SPACING,
    /** Hebrew text with niqqud added, once its download is on the phone. */
    ADD_NIKUD,
    /** Arabic text with tashkeel added, once its download is on the phone. */
    ADD_TASHKEEL,
    /** Japanese text with the readings of its kanji in brackets, once its download is on the phone. */
    ADD_FURIGANA,
    /** The other alphabet of a language written in two, while that language is being typed. */
    SWITCH_SCRIPT,
    /**
     * Indic text, or IAST and the other romanisations, rewritten in another
     * script or scheme (vidyut-lipi, its own download). A door: the bar opens
     * a ladder of the targets, the selection's own script read from its
     * Unicode block ([ScriptConvert]).
     */
    CONVERT_SCRIPT,
    /** Russian text with its ё put back, once its download is on the phone. */
    RESTORE_YO,
    /** Persian text with its half-spaces and letter forms corrected. */
    PERSIAN_SPACING,
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
            KOREAN_SPACING -> R.string.core_content_selection_macro_korean_spacing
            ADD_NIKUD -> R.string.core_content_selection_macro_add_nikud
            ADD_TASHKEEL -> R.string.core_content_selection_macro_add_tashkeel
            ADD_FURIGANA -> R.string.core_content_selection_macro_add_furigana
            SWITCH_SCRIPT -> R.string.core_content_selection_macro_switch_script
            CONVERT_SCRIPT -> R.string.core_content_selection_macro_convert_script
            RESTORE_YO -> R.string.core_content_selection_macro_restore_yo
            PERSIAN_SPACING -> R.string.core_content_selection_macro_persian_spacing
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
            UNDO, SELECT_ALL, COPY, CUT, PASTE, DELETE, FIND, REPLACE, REPEAT, SAVE_NOTE, SNIPPET -> MacroCategory.EDITING
            LINES_SORT, LINES_DEDUPE, LINES_NUMBER, LINES_BULLET,
            LINES_CHECKBOX, LINES_UP, LINES_DOWN,
            -> MacroCategory.LINES
            FORMAT, FANCY, CHAT_BOLD, CHAT_ITALIC, CHAT_STRIKE, CHAT_MONO,
            CASE_LOWER, CASE_TITLE, CASE_UPPER, CASE_SENTENCE,
            CASE_CAMEL, CASE_SNAKE, CASE_KEBAB, CASE_CONSTANT -> MacroCategory.FORMAT
            DIGITS_LATIN, ZAWGYI, KOREAN_SPACING, ADD_NIKUD, ADD_TASHKEEL, ADD_FURIGANA, SWITCH_SCRIPT, CONVERT_SCRIPT, RESTORE_YO, PERSIAN_SPACING, COLOUR, JSON_FORMAT, BASE64_DECODE, URL_DECODE, STRIP_TRACKERS -> MacroCategory.CONVERT
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
        get() = !leavesApp && this != PASTE && this != READ_ALOUD && this != SAVE_NOTE && this != SNIPPET && this != EDIT_WORD && this != KOREAN_SPACING && this != ADD_NIKUD && this != ADD_TASHKEEL && this != ADD_FURIGANA && this != RESTORE_YO && this != CONVERT_SCRIPT

    /** A door rather than an action: the bar opens a ladder for it and the service never sees the tap. */
    val opensLadder: Boolean
        get() = this == FORMAT || this == FANCY || this == COLOUR || this == TIME_ZONES || this == CONVERT_SCRIPT
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
        // Shared by ClearURLs, AdGuard's URL tracking filter and uBlock
        // Origin's privacy list (#1470): names no page reads, only an ad
        // network, a mail tool or an app-install tracker.
        "__twitter_impression", "action_object_map", "action_type_map", "action_ref_map",
        "fb_action_ids", "fb_action_types", "fb_source", "fb_ref", "fb_comment_id", "fbadid",
        "mc_tc", "os_ehash", "tracking_source", "wtrid", "wt_mc", "wt_zmc", "echobox", "cmpid", "gs_l",
        "__io_lv", "_io_session_id", "_bdadid", "_bhlid", "_clde", "_cldee", "_ly_c", "_ly_r", "_ope", "_zucks_suid",
        "adc_publisher", "adc_token", "adfrom", "admitad_uid", "adobe_mc_ref", "adobe_mc_sdid",
        "adsterra_clid", "adsterra_placement_id", "aiad_clid", "analytics_context", "analytics_trace_id",
        "adj_campaign", "adj_creative", "adj_label", "adj_t",
        "af_ad", "af_adset", "af_click_lookback", "af_force_deeplink", "af_xp",
        "at_campaign", "at_campaign_type", "at_creation", "at_emailtype", "at_link", "at_link_id", "at_link_origin",
        "at_link_type", "at_medium", "at_ptr_name", "at_recipient_id", "at_recipient_list", "at_send_date", "at_custom",
        "asgtbndr", "awc", "bance_xuid", "bemobdata", "beyond_uzcvid", "beyond_uzmcvid", "btag",
        "cm_cr", "cm_me", "cm_mmc", "cstrackid", "cx_click", "cx_recsorder", "cx_recswidget",
        "elq", "elqaid", "elqak", "elqat", "elqcampaignid", "elqtrackid", "emcs_t", "eml-mediaplan", "eml-name",
        "eml-publisher", "ems_dl", "erid", "external_click_id", "famad_xuid", "ftag", "gci", "gps_adid",
        "guccounter", "guce_referrer", "guce_referrer_sig", "iclid", "int_campaign", "int_content", "int_medium",
        "int_source", "int_term", "ir_adid", "ir_campaignid", "ir_partnerid", "is_retargeting", "janet", "jmtyclid",
        "ldtag_cl", "line_uid", "loclid", "lt_r", "maf", "mindbox-click-id", "mindbox-message-key",
        "mt_adset", "mt_campaign", "mt_click_id", "mt_creative", "mt_link_id", "mt_medium", "mt_network",
        "mt_sub1", "mt_sub2", "mt_sub3", "mt_sub4", "mt_sub5", "nb_expid_meta", "nb_placement", "nx_source",
        "ns_campaign", "ns_mchannel", "ns_source", "ns_linkname", "ns_fee", "oprtrack", "pjclid",
        "raneaid", "ranmid", "ransiteid", "rtkcid", "sscid", "spot_im_redirect_source", "srclt", "taid",
        "tcsack", "tduid", "tgclid", "tw_medium", "tw_profile_id", "tw_source", "unicorn_click_id",
        "user_email_address", "usqp", "uzcid", "vc_lpp", "vs_campaign_id", "vsm_cid", "vsm_pid", "vsm_type",
        "winflncrtag", "x-a-medium", "x-clickref", "x-source", "xtor", "yj_r", "ym_tracking_id", "ymid", "ysclid",
        "dpg_campaign", "dpg_content", "dpg_medium", "dpg_source", "personaclick_input_query",
        "personaclick_search_query", "recommended_by", "recommended_code", "ebisadid", "ck_subscriber_id",
        "ss_email_id", "clickid", "sp_cid",
    )

    /** Google Analytics, Matomo, Piwik, HubSpot and Oracle campaigns: a family each. */
    private val TRACKING_PREFIXES = listOf(
        "utm_", "mtm_", "matomo_", "pk_", "hsa_", "oft_",
        // Internal and on-site campaigns, Blueshift, Adjust, Yahoo's
        // e-commerce ids, Sendgrid's tags (#1470).
        "itm_", "otm_", "hmb_", "bsft_", "adjust_", "ebisother", "_sgm_",
    )

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
        /** Only paths this finds a match in, for a rule about one kind of page (a product, a post). */
        val pathPattern: Regex? = null,
        /**
         * The page's whole list of parameters that matter, when it is known
         * (the idea is Link Cleaner's): on a matching page every other
         * parameter goes, tracker or not. Only for pages whose address is
         * fully understood, a product or a post, never a whole site.
         */
        val keep: Set<String>? = null,
        /** The path rewritten to its short form: a tracking segment or a product's name taken out. */
        val pathRewrite: ((String) -> String)? = null,
        /** A [keep] list safe without asking: the page has no parameters of its own (a post, a TikTok video). */
        val always: Boolean = false,
    ) {
        /**
         * A rule that drops what is not a known tracker ([keep]) only runs
         * when the user asked for it with [LinkRewrite.KNOWN_PAGES] or
         * [LinkRewrite.STRICT]: by default nothing but trackers comes off,
         * except on the [always] pages.
         */
        val thorough: Boolean get() = keep != null && !always

        fun matches(host: String, linkPath: String): Boolean =
            domains.any { hostMatches(host, it) } && linkPath.startsWith(path) &&
                pathPattern?.containsMatchIn(linkPath) != false

        fun takes(name: String): Boolean =
            name in params || prefixes.any { name.startsWith(it) } || (keep != null && name !in keep)

        fun cleanPath(url: String): String {
            val rewrite = pathRewrite ?: return url
            val parts = UrlParts.of(url)
            if (parts.path.isEmpty()) return url
            val cleaned = rewrite(parts.path)
            return if (cleaned == parts.path) url else parts.copy(path = cleaned).toString()
        }
    }

    /** An Amazon product number where the path names one: `/dp/B0…`, `/gp/product/B0…`. */
    private val AMAZON_ASIN = Regex("""/(?:dp|gp/product|gp/aw/d|product|exec/obidos/asin)/([A-Z0-9]{10})(?=[/?#]|$)""")

    private val AMAZON_REF = Regex("/ref=[^/]*$")

    /** `/Lamp-Shade-Linen/dp/B0ABCDEFGH/ref=sr_1_3` is `/dp/B0ABCDEFGH`. */
    private fun amazonPath(path: String): String =
        AMAZON_ASIN.find(path)?.let { "/dp/" + it.groupValues[1] } ?: AMAZON_REF.replace(path, "")

    private fun pathRewrite(pattern: Regex, replacement: String): (String) -> String = { path ->
        if (pattern.matches(path)) pattern.replace(path, replacement) else path
    }

    private val SITE_RULES = listOf(
        // A product page is its number: the name in front of it, the
        // referral after it and every parameter go, as Link Cleaner does.
        // Amazon keeps nothing; a variant has a number of its own.
        SiteRule(
            listOf("amazon."),
            pathPattern = AMAZON_ASIN,
            keep = emptySet(),
            pathRewrite = { amazonPath(it) },
        ),
        SiteRule(
            listOf("amazon.", "amzn."),
            params = setOf(
                "content-id", "crid", "sprefix", "dib", "dib_tag", "qid", "sr", "ref_", "_encoding",
                "linkcode", "linkid", "camp", "creative", "creativeasin", "ascsubtag", "spla", "spia",
                "social_share", "starsleft", "skipredirect", "smid_share", "tag",
                // Paid-search and affiliate ids (#1470).
                "hvadid", "hvbmt", "hvdev", "hvlocphy", "hvnetw", "hvqmt", "hvrand", "hvtargid", "hvpone",
                "hvptwo", "hvpos", "hydadcr", "adgrpid", "associatetag", "dchild", "aaxitk", "refrid", "reftag",
                "store_ref", "twitchreferral", "ytwchpos", "lp_asin", "visitid", "ts_id",
                "shoppingportalenabled", "isdramintegrated", "muclientname", "ingress", "plattr", "pbs",
            ),
            prefixes = listOf("pd_rd_", "pf_rd_"),
            pathRewrite = { AMAZON_REF.replace(it, "") },
        ),
        SiteRule(
            listOf("bestbuy.com"),
            pathPattern = Regex("""^/site/(?:.+/)?\d+\.p/?$"""),
            keep = emptySet(),
            pathRewrite = pathRewrite(Regex("""^/site/(?:.+/)?(\d+\.p)/?$"""), "/site/$1"),
        ),
        SiteRule(
            listOf("walmart.com"),
            pathPattern = Regex("""^/ip/(?:[^/]+/)?\d+/?$"""),
            keep = emptySet(),
            pathRewrite = pathRewrite(Regex("""^/ip/(?:[^/]+/)?(\d+)/?$"""), "/ip/$1"),
        ),
        SiteRule(
            listOf("ebay."),
            pathPattern = Regex("""^/itm/(?:[^/]+/)?\d+/?$"""),
            keep = setOf("var"),
            pathRewrite = pathRewrite(Regex("""^/itm/(?:[^/]+/)?(\d+)/?$"""), "/itm/$1"),
        ),
        SiteRule(
            listOf("etsy.com"),
            pathPattern = Regex("""^(?:/[a-z]{2}(?:-[a-z]{2})?)?/listing/\d+(?:/[^/]*)?$"""),
            keep = emptySet(),
            pathRewrite = pathRewrite(Regex("""^((?:/[a-z]{2}(?:-[a-z]{2})?)?/listing/\d+)(?:/[^/]*)?$"""), "$1"),
        ),
        SiteRule(listOf("aliexpress."), pathPattern = Regex("""^/item/\d+\.html$"""), keep = emptySet()),
        SiteRule(listOf("youtube.com"), path = "/watch", keep = setOf("v", "t", "list", "index")),
        SiteRule(listOf("youtube.com"), path = "/shorts/", keep = emptySet()),
        SiteRule(listOf("youtu.be"), keep = setOf("t", "list", "index")),
        // Posts and videos whose every parameter is about the share, never
        // about the page (#1470): these keep only what opens them even by
        // default, because nothing a reader needs can be lost.
        SiteRule(listOf("twitter.com", "x.com"), pathPattern = Regex("""^/[^/]+/status/\d+"""), keep = emptySet(), always = true),
        SiteRule(listOf("bsky.app"), pathPattern = Regex("""^/profile/[^/]+/post/"""), keep = emptySet(), always = true),
        SiteRule(listOf("threads.net", "threads.com"), pathPattern = Regex("""^/@[^/]+/post/"""), keep = emptySet(), always = true),
        SiteRule(listOf("instagram.com"), pathPattern = Regex("""^/(?:[^/]+/)?(?:p|reels?|tv)/"""), keep = setOf("img_index"), always = true),
        SiteRule(listOf("tiktok.com"), pathPattern = Regex("""^/@[^/]*/(?:video|photo)/\d+"""), keep = emptySet(), always = true),
        SiteRule(
            listOf("open.spotify.com"),
            pathPattern = Regex("""^/(?:intl-[a-z]+/)?(?:track|album|playlist|episode|show|artist)/"""),
            keep = setOf("t"),
            always = true,
        ),
        SiteRule(listOf("reddit.com"), pathPattern = Regex("""^/r/[^/]+/comments/"""), keep = setOf("context")),
        SiteRule(listOf("facebook.com"), pathPattern = Regex("""^/(?:story|permalink)\.php$"""), keep = setOf("story_fbid", "id")),
        SiteRule(listOf("linkedin.com"), pathPattern = Regex("""^/(?:posts|feed/update|jobs/view)/"""), keep = emptySet()),
        SiteRule(listOf("pinterest."), pathPattern = Regex("""^/pin/\d+"""), keep = emptySet()),
        SiteRule(listOf("play.google.com"), path = "/store/apps/details", keep = setOf("id")),
        SiteRule(listOf("xiaohongshu.com"), pathPattern = Regex("""^/(?:explore|discovery/item)/"""), keep = setOf("xsec_token")),
        SiteRule(listOf("webtoons.com"), pathPattern = Regex("""/(?:viewer|list)$"""), keep = setOf("title_no", "episode_no")),
        SiteRule(listOf("macys.com"), path = "/shop/product/", keep = setOf("id")),
        SiteRule(
            listOf("youtube.com", "youtu.be"),
            params = setOf("feature", "pp", "kw", "embeds_referring_euri", "embeds_referring_origin", "source_ve_path"),
        ),
        SiteRule(listOf("twitter.com", "x.com"), params = setOf("s", "t", "cn", "ref_src", "ref_url")),
        SiteRule(
            listOf("instagram.com"),
            params = setOf(
                "igsh", "igshid", "ig_rid", "igsi", "click_source", "cplk", "dlrf", "exln", "mdxt", "ndid", "obrf",
                "psln", "rpxt", "srtk", "stkn", "vrfl", "xtok",
            ),
        ),
        SiteRule(listOf("threads.net", "threads.com"), params = setOf("xmt", "slof")),
        SiteRule(
            listOf("facebook.com", "fb.com", "fb.watch", "messenger.com"),
            params = setOf(
                "mibextid", "rdid", "share_url", "sfnsn", "extid", "paipv", "eav", "comment_tracking",
                "_rdr", "_nc_x", "action_history", "dti", "ftentidentifier", "ls_ref", "referral_code",
                "referral_story_type", "tracking", "video_source", "wtsid", "hc_ref", "hc_location", "notif_id",
                "notif_t", "ref_component", "ref_page", "acontext", "privacy_mutation_token",
            ),
            prefixes = listOf("__tn__", "__cft__", "__xts__", "__eep__"),
        ),
        SiteRule(
            listOf("tiktok.com"),
            params = setOf(
                "_r", "_t", "is_from_webapp", "sender_device", "sender_web_id", "share_app_id",
                "share_link_id", "share_item_id", "share_author_id", "tt_from", "u_code", "timestamp",
                "user_id", "social_sharing", "checksum", "sec_user_id", "enter_from", "is_copy_url",
                "web_id", "refer",
                // What a TikTok share link carries besides the video (#1470).
                "_d", "preview_pb", "sharer_language", "share_iid", "share_app_name", "share_region", "share_source",
                "social_share_type", "ugbiz_name", "ug_btm", "sp_root_share_link_id", "sp_level", "sp_root_u",
                "sp_root_d", "link_reflow_popup_iteration_sharer", "panel_source_v2", "share_enter_from",
                "item_author_type", "enable_checksum", "embed_source", "enter_method", "referer_url",
                "referer_video_id", "sec_uid", "cachesdk", "trackparams", "region", "source", "_source_",
            ),
        ),
        SiteRule(
            listOf("reddit.com", "redd.it"),
            params = setOf(
                "share_id", "rdt", "\$deep_link", "\$3p", "\$original_url", "%24deep_link", "%243p", "%24original_url",
                "correlation_id", "ref_campaign", "ref_source", "_branch_match_id", "entry_point", "post_fullname",
                "post_index", "referrer_domain", "referrer_url", "target_user", "geoip_country",
            ),
        ),
        SiteRule(listOf("spotify.com"), params = setOf("si", "nd", "pi", "dl_branch", "dlsi", "referral", "sp_cid", "context")),
        SiteRule(
            listOf("linkedin.com"),
            params = setOf(
                "trk", "trkinfo", "trackingid", "lipi", "midtoken", "midsig", "eid", "otptoken",
                "refid", "originalsubdomain", "rcm", "lici", "isss", "original_referer", "originalreferer",
                "referenceid", "upsellorderorigin", "courseclaim", "u",
            ),
        ),
        SiteRule(
            listOf("aliexpress."),
            params = setOf(
                "scm", "pvid", "algo_pvid", "algo_exp_id", "btsid", "ws_ab_test", "terminal_id",
                "gatewayadapt", "_randl_currency", "_randl_shipto", "gps-id", "pdp_npi", "pdp_ext_f",
                "utparam", "sk", "af", "cv", "cn", "dp", "src", "sourcetype",
                "algo_expid", "mall_affr", "curpageloguid", "scm-url", "scm_id", "utparam-url", "spm",
            ),
            prefixes = listOf("aff_"),
        ),
        SiteRule(
            listOf("ebay."),
            params = setOf(
                "_trkparms", "_trksid", "amdata", "hash", "mkcid", "mkevt", "mkrid", "campid", "toolid",
                "customid", "_from", "ssspo", "sssrc", "ssuid", "widget_ver", "itmmeta", "itmprp", "_skw", "epid",
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
        // More of what ClearURLs, AdGuard and uBlock Origin take per site (#1470).
        SiteRule(listOf("play.google.com"), params = setOf("referrer", "pcampaignid")),
        SiteRule(
            listOf("temu.com"),
            params = setOf(
                "adg_ctx", "freesia_scene", "from_share", "mrk_rec", "refer_page_el_sn", "refer_page_id",
                "refer_page_name", "refer_page_sn", "refer_share_channel", "refer_share_id", "refer_share_suin",
                "refer_source", "share_img", "spec_gallery_id", "top_gallery_url", "g_ccy", "g_lg", "g_region", "g_site",
            ),
            prefixes = listOf("_x_", "_oak_", "_p_", "_bg_"),
        ),
        SiteRule(
            listOf("bilibili.com", "b23.tv"),
            params = setOf(
                "bbid", "broadcast_type", "buvid", "callback", "from", "from_source", "from_spmid", "from_spm_id",
                "is_room_feed", "is_story_h5", "mid", "msource", "csource", "plat_id", "refer_from", "seid",
                "session_id", "share_from", "share_medium", "share_plat", "share_session_id", "share_source",
                "share_tag", "spm_id_from", "spmid", "timestamp", "ts", "unique_k", "up_id", "vd_source", "visit_id",
                "caid", "creative_id", "linked_creative_id", "request_id", "resource_id", "track_id", "trackid",
            ),
        ),
        SiteRule(
            listOf("taobao.com", "tmall.com"),
            params = setOf(
                "abbucket", "abtest", "acm", "algo_expid", "algo_pvid", "ali_refid", "ali_trackid", "bftrwd", "bfttag",
                "cpp", "impid", "lygclk", "mytmenu", "ns", "pvid", "scm", "share_crt_v", "shareurl", "short_name",
                "sourcetype", "sp_tk", "spm", "suid", "trackinfo", "turing_bucket", "un", "ut_sk", "utkn", "utparam",
                "app_pvid", "traceid", "xid", "activityid", "ptl",
            ),
        ),
        SiteRule(
            listOf("lazada."),
            params = setOf(
                "abid", "ad_src", "clicktrackinfo", "did", "impsrc", "mp", "pa", "pid_pvid", "pos", "pvid", "scm",
                "spm", "src", "laz_trackid", "from",
            ),
        ),
        SiteRule(listOf("daraz."), params = setOf("spm", "scm", "pvid", "clicktrackinfo", "mp", "from", "laz_trackid")),
        SiteRule(listOf("shopee."), params = setOf("publish_id", "sp_atk", "xptdk", "uls_trackid", "smtt")),
        SiteRule(
            listOf("flipkart.com"),
            params = setOf(
                "cid", "lid", "pid_ref", "collection-tab-name", "ctx", "fm", "hpid", "nnc", "otracker", "otracker1",
                "ppn", "ppt", "qh", "srno", "ssid", "_refid", "affid", "iid", "pageuid", "pwsvid", "store",
            ),
        ),
        SiteRule(listOf("netflix.com"), params = setOf("tctx", "trackid", "source")),
        SiteRule(listOf("imdb.com"), params = setOf("ref_"), prefixes = listOf("pf_rd_")),
        SiteRule(listOf("twitch.tv"), params = setOf("tt_content", "tt_medium", "web_only")),
        SiteRule(listOf("apple.com"), params = setOf("itscg", "itsct", "app", "ct", "pt", "mttnsubad")),
        SiteRule(listOf("nytimes.com"), params = setOf("smid", "smtyp", "impression_id", "referringsource", "sgrp")),
        SiteRule(listOf("washingtonpost.com"), params = setOf("itid")),
        SiteRule(listOf("substack.com"), params = setOf("isfreemail", "triedredirect", "r")),
        SiteRule(listOf("github.com"), params = setOf("email_source", "email_token", "reference_location")),
        SiteRule(listOf("weibo.com", "weibo.cn"), params = setOf("dt_dapp", "weibo_id")),
        SiteRule(listOf("snapchat.com"), params = setOf("sc_referrer", "sc_ua", "share_id", "sender_web_id")),
        SiteRule(listOf("quora.com"), params = setOf("al_imp", "share")),
        SiteRule(listOf("airbnb."), params = setOf("federated_search_id", "search_type", "source_impression_id", "source")),
        SiteRule(listOf("zillow.com"), params = setOf("rtoken")),
        SiteRule(listOf("walmart.com"), params = setOf("u1"), prefixes = listOf("ath")),
        SiteRule(listOf("bestbuy.com"), params = setOf("acampid", "intl", "loc", "mpid", "adcampaignid", "sharedid", "subid1", "subid2", "subid3", "svlink", "vehicle")),
        SiteRule(listOf("store.steampowered.com", "steamcommunity.com"), params = setOf("snr")),
        SiteRule(listOf("yahoo.com"), params = setOf("guccounter", "guce_referrer", "guce_referrer_sig", "ncid", "__ysp", "soc_src", "soc_trk")),
        SiteRule(listOf("pinterest.", "pin.it"), params = setOf("invite_code", "sender", "sfo")),
        SiteRule(
            listOf("google."),
            params = setOf(
                "ved", "ei", "sca_esv", "sca_upv", "oq", "gs_lcrp", "gs_lp", "gs_lcp", "gs_ssp", "sourceid",
                "sxsrf", "iflsig", "sclient", "rlz", "bih", "biw", "dpr", "uact", "aqs", "client", "stick",
                "sei", "mstk", "csui", "prmd", "fbs", "vet", "sa", "source", "ie",
                "atyp", "cad", "cd", "cshid", "dcr", "esrc", "ictx", "je", "uds", "sstk", "g_ep", "gws_rd", "gfe_rd",
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
    private class Redirect(
        val domain: String,
        val path: String,
        val params: Set<String>,
        /** The target is the whole query, not one parameter: `href.li/?https://…`. */
        val wholeQuery: Boolean = false,
        /** The target is a path on this site: YouTube's `attribution_link?u=/watch…`. */
        val relativeTo: String? = null,
        /** The target is URL-safe Base64 after this prefix: Bing's `ck/a?u=a1aHR0…`. */
        val base64Prefix: String? = null,
    )

    private val REDIRECTS = listOf(
        Redirect("href.li", "/", emptySet(), wholeQuery = true),
        Redirect("google.", "/aclk", setOf("adurl")),
        Redirect("googleadservices.com", "/", setOf("adurl")),
        Redirect("rover.ebay.", "/", setOf("mpre")),
        Redirect("click.redditmail.com", "/", setOf("url")),
        Redirect("exactag.com", "/", setOf("url")),
        Redirect("flexlinkspro.com", "/", setOf("url")),
        Redirect("awin1.com", "/", setOf("ued", "p")),
        Redirect("tradedoubler.com", "/", setOf("url")),
        Redirect("go.skimresources.com", "/", setOf("url")),
        Redirect("shareasale.com", "/r.cfm", setOf("urllink")),
        Redirect("t.cfjump.com", "/", setOf("url")),
        Redirect("prf.hn", "/", setOf("destination")),
        Redirect("app.adjust.com", "/", setOf("redirect", "fallback")),
        Redirect("anon.to", "/", emptySet(), wholeQuery = true),
        Redirect("dereferer.me", "/", emptySet(), wholeQuery = true),
        Redirect("nullrefer.com", "/", emptySet(), wholeQuery = true),
        Redirect("youtube.com", "/attribution_link", setOf("u"), relativeTo = "https://www.youtube.com"),
        Redirect("bing.com", "/ck/a", setOf("u"), base64Prefix = "a1"),
        Redirect("cts.businesswire.com", "/", setOf("url")),
        Redirect("l.wl.co", "/l", setOf("u")),
        Redirect("link.zhihu.com", "/", setOf("target")),
        Redirect("linkedin.com", "/safety/go", setOf("url")),
        Redirect("pinterest.", "/offsite/", setOf("url")),
        Redirect("getpocket.com", "/redirect", setOf("url")),
        Redirect("t.me", "/iv", setOf("url")),
        Redirect("deref-gmx.net", "/", setOf("redirecturl")),
        Redirect("deref-web.de", "/", setOf("redirecturl")),
        Redirect("statics.teams.cdn.office.net", "/", setOf("url")),
        Redirect("away.vk.com", "/away.php", setOf("to")),
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
        SelectionMacro.DIGITS_LATIN, SelectionMacro.ZAWGYI, SelectionMacro.KOREAN_SPACING, SelectionMacro.ADD_NIKUD, SelectionMacro.ADD_TASHKEEL, SelectionMacro.ADD_FURIGANA,
        SelectionMacro.SWITCH_SCRIPT, SelectionMacro.CONVERT_SCRIPT, SelectionMacro.RESTORE_YO, SelectionMacro.PERSIAN_SPACING, SelectionMacro.COLOUR, SelectionMacro.FANCY,
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
        SelectionMacro.DIGITS_LATIN, SelectionMacro.ZAWGYI, SelectionMacro.KOREAN_SPACING, SelectionMacro.ADD_NIKUD, SelectionMacro.ADD_TASHKEEL, SelectionMacro.ADD_FURIGANA,
        SelectionMacro.SWITCH_SCRIPT, SelectionMacro.CONVERT_SCRIPT, SelectionMacro.RESTORE_YO, SelectionMacro.PERSIAN_SPACING, SelectionMacro.COLOUR,
        SelectionMacro.CALL, SelectionMacro.SMS, SelectionMacro.WHATSAPP, SelectionMacro.EMAIL,
        SelectionMacro.OPEN, SelectionMacro.QR, SelectionMacro.ADD_CONTACT, SelectionMacro.FANCY,
        SelectionMacro.STRIP_TRACKERS, SelectionMacro.EDIT_WORD,
    )

    /**
     * Bumped when [defaultMacros] or [defaultOrder] change in a way every
     * existing user should receive. A stored list under an older version
     * keeps the user's choices and gains the new shipped actions
     * ([SelectionMacroCodec.decodeMacros]); it is no longer a reset.
     */
    // 10: Convert script, which shows nothing until its library is on the phone.
    const val LIST_VERSION = 10

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
        val masks = PhoneFormats.parseAll(phoneFormats)
        if (masks.isNotEmpty()) return PhoneFormats.matches(trimmed, masks)
        // No masks: the numbering plans, where there is a table and a country.
        return com.wasimaster.wmkeyboard.core.phone.PhoneNumbers.check(trimmed) ?: true
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
        SelectionMacro.KOREAN_SPACING -> gates.koreanSpacing && gates.content.hasHangul
        SelectionMacro.ADD_NIKUD -> gates.hebrewNikud && gates.content.hasHebrew
        SelectionMacro.ADD_TASHKEEL -> gates.arabicTashkeel && gates.content.hasArabicScript && !gates.content.hasPersianLetters
        SelectionMacro.ADD_FURIGANA -> gates.japaneseFurigana && gates.content.hasKanji && (gates.languageId == "ja" || gates.content.hasKana)
        SelectionMacro.SWITCH_SCRIPT -> gates.languageId in ScriptSwitch.languages && (gates.content.hasCyrillic || gates.content.hasLatin)
        SelectionMacro.CONVERT_SCRIPT -> gates.scriptConvert && ScriptConvert.offered(gates.content, gates.languageId)
        SelectionMacro.RESTORE_YO -> gates.russianYo && gates.languageId == "ru" && gates.content.hasCyrillic
        SelectionMacro.PERSIAN_SPACING -> gates.content.hasArabicScript && (gates.languageId == "fa" || gates.content.hasPersianLetters)
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
    fun format(
        selection: String,
        kind: SelectionKind,
        phoneFormats: List<String> = emptyList(),
        links: LinkCleaning = LinkCleaning(),
    ): String? {
        val trimmed = selection.trim()
        val formatted = when (kind) {
            SelectionKind.PHONE -> formatPhone(trimmed, phoneFormats)
            SelectionKind.EMAIL -> trimmed.lowercase()
            SelectionKind.URL -> formatUrl(trimmed, links)
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
     * With no matching mask, the number is written the way the OS writes
     * numbers for the phone's country (its own copy of libphonenumber; see
     * `PhoneNumbers.format`): national for a local number, international for
     * a foreign one. Where the OS cannot say, E.164 when the number already
     * declares a country code, and the bare digit run otherwise.
     */
    fun formatPhone(number: String, phoneFormats: List<String>): String? {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val masks = PhoneFormats.parseAll(phoneFormats)
        // The user's masks win wherever one matches (see PhoneFormats.render).
        if (masks.any { PhoneFormats.matches(number, listOf(it)) }) return PhoneFormats.render(number, masks)
        com.wasimaster.wmkeyboard.core.phone.PhoneNumbers.format(number)?.let { return it }
        val explicit = number.trimStart().startsWith("+")
        return if (explicit) "+$digits" else digits
    }

    /** [url] with its tracking parameters removed, and a scheme added when it had none. */
    fun formatUrl(url: String, links: LinkCleaning = LinkCleaning()): String? {
        val withScheme = if (url.contains("://")) url else "https://$url"
        return stripTrackers(withScheme, links) ?: withScheme
    }

    /** [stripTrackers] with only the user's own tracker names on top of the built-in ones. */
    fun stripTrackers(url: String, extra: Collection<String> = emptyList()): String? =
        stripTrackers(url, LinkCleaning(extra))

    /**
     * [url] without its tracking parameters, or null when it carries none.
     *
     * Four things, all of them provably not about which page it is (#762):
     * a redirect wrapper (Google's `/url?q=`, Facebook's `l.php?u=`, an
     * Outlook safe link, Bing's Base64 `ck/a`) is unwrapped to the link
     * inside it; the query loses the global trackers plus the ones a known
     * site adds ([SITE_RULES]); Amazon's `/ref=…` path segment goes; and, only
     * when the user switched on [LinkRewrite.KNOWN_PAGES], a page whose
     * address is fully known (an Amazon product, a YouTube video, a post on
     * X) loses every parameter it does not need and a product page's path is
     * cut to its number, so `/Lamp-Shade/dp/B0ABCDEFGH/ref=sr_1_3` is
     * `/dp/B0ABCDEFGH`. That is Link Cleaner's way and it can drop something
     * that mattered (a seller, a variant), which is why it is a choice. The host,
     * the fragment and any other path stay, because rewriting those is how a
     * "clean this link" feature quietly breaks the link, unless the user
     * asked for one of [LinkCleaning.rewrites]. Nor is a scheme added: the
     * link keeps the shape it was written in.
     */
    fun stripTrackers(url: String, links: LinkCleaning): String? {
        val trimmed = url.trim()
        var link = trimmed
        // A wrapper can wrap another (an Outlook safe link round a Google
        // one), so unwrap until the link stands on its own, a few times at most.
        for (i in 0 until MAX_REDIRECT_DEPTH) link = unwrapRedirect(link) ?: break
        val host = hostOf(link)
        val path = pathOf(link)
        val strict = LinkRewrite.STRICT in links.rewrites
        val thorough = strict || LinkRewrite.KNOWN_PAGES in links.rewrites
        val sites = SITE_RULES.filter { (thorough || !it.thorough) && it.matches(host, path) }
        link = stripQuery(link, links.extra, sites, strict) ?: link
        for (site in sites) link = site.cleanPath(link)
        if (links.rewrites.isNotEmpty()) link = rewrite(link, links.rewrites)
        return link.takeIf { it != trimmed }
    }

    /** [url] without the query parameters [isTracker] names, or null when there are none. */
    private fun stripQuery(url: String, extra: Collection<String>, sites: List<SiteRule>, strict: Boolean): String? {
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
            name.isNotEmpty() && !isTracker(name, extra, sites) && !(strict && !strictKeeps(name, sites))
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
        if (rule.wholeQuery) {
            // Everything after the first `?`, fragment and all: the target's
            // own query and hash are part of it.
            val raw = url.substringAfter('?', "")
            val target = if (raw.contains("://")) raw else runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
            return target?.trim()?.takeIf { isWholeLink(it) }
        }
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            if (part.substringBefore('=').lowercase() !in rule.params) continue
            var target = runCatching {
                URLDecoder.decode(part.substringAfter('=', ""), "UTF-8").trim()
            }.getOrNull() ?: continue
            val prefix = rule.base64Prefix
            if (prefix != null) {
                if (!target.startsWith(prefix)) continue
                target = decodeBase64Url(target.removePrefix(prefix)) ?: continue
            }
            val base = rule.relativeTo
            if (base != null && target.startsWith('/') && !target.startsWith("//")) target = base + target
            if (isWholeLink(target)) return target
        }
        return null
    }

    private fun isWholeLink(target: String): Boolean {
        val isLink = target.startsWith("https://", ignoreCase = true) ||
            target.startsWith("http://", ignoreCase = true)
        return isLink && target.none { it.isWhitespace() }
    }

    /** URL-safe Base64, padded or not, as text; null when it is not. */
    private fun decodeBase64Url(value: String): String? = runCatching {
        val bytes = kotlin.io.encoding.Base64.UrlSafe
            .withPadding(kotlin.io.encoding.Base64.PaddingOption.PRESENT_OPTIONAL)
            .decode(value)
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    /** [url]'s host, lower case and without a leading `www.`; the link need not have a scheme. */
    private fun hostOf(url: String): String = UrlParts.of(url).host.removePrefix("www.")

    /** [url]'s path, `/` when it has none. */
    private fun pathOf(url: String): String = UrlParts.of(url).path.ifEmpty { "/" }

    /**
     * Parameters [LinkRewrite.STRICT] leaves on every site: the ones that are
     * the page itself (a search, an id, a page number), plus whatever a
     * matching site rule says its page needs.
     */
    private val STRICT_KEEPS = setOf(
        "q", "query", "search", "search_query", "keyword", "keywords", "k", "s", "id", "v", "p", "page", "lang",
    )

    private fun strictKeeps(name: String, sites: List<SiteRule>): Boolean =
        name in STRICT_KEEPS || sites.any { it.keep?.contains(name) == true }

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
    fun stripTrackersInText(text: String, extra: Collection<String> = emptyList()): String? =
        stripTrackersInText(text, LinkCleaning(extra))

    /** [stripTrackersInText] with the user's [links] options. */
    fun stripTrackersInText(text: String, links: LinkCleaning): String? {
        if (!mayCarryTrackers(text, links)) return null
        var changed = false
        val out = NON_SPACE_RUN.replace(text) { match ->
            val run = match.value
            if (!mayCarryTrackers(run, links)) return@replace run
            val core = run.trimEnd { it in LINK_TRAILING_PUNCTUATION }
            val tail = run.substring(core.length)
            if (core.length > MAX_URL_LENGTH || !isUrl(core)) return@replace run
            val clean = stripTrackers(core, links) ?: return@replace run
            changed = true
            clean + tail
        }
        return out.takeIf { changed }
    }

    /**
     * The cheap test before the real one: every tracker [stripTrackers] takes
     * sits in a query, except the product paths of [SITE_RULES] like Amazon's,
     * and the [LinkCleaning.rewrites] each have a host or a `%` to look for.
     */
    private fun mayCarryTrackers(text: String, links: LinkCleaning = LinkCleaning()): Boolean {
        if (text.contains('?') || text.contains("/ref=")) return true
        val rewrites = links.rewrites
        if (rewrites.isEmpty()) return false
        val thorough = LinkRewrite.KNOWN_PAGES in rewrites || LinkRewrite.STRICT in rewrites
        if (thorough && PATH_HINTS.any { text.contains(it) }) return true
        return (LinkRewrite.YOUTUBE_SHORTS_AS_VIDEOS in rewrites && text.contains("/shorts/")) ||
            (LinkRewrite.TWITTER_EMBEDS in rewrites && (text.contains("x.com") || text.contains("twitter.com"))) ||
            (LinkRewrite.BLUESKY_EMBEDS in rewrites && text.contains("bsky.app")) ||
            (LinkRewrite.YOUTUBE_SHORT in rewrites && text.contains("/shorts/")) ||
            (LinkRewrite.READABLE in rewrites && text.contains('%'))
    }

    /** Paths [SITE_RULES] shorten without a query to go by. */
    private val PATH_HINTS = listOf("/ref=", "/dp/", "/gp/", "/ip/", "/itm/", "/listing/", "/site/")

    /**
     * [link] with the user's [rewrites] made (Link Cleaner's options, and
     * [LinkRewrite.READABLE]), after the trackers are off. The link keeps its
     * scheme, or its lack of one.
     */
    private fun rewrite(link: String, rewrites: Set<LinkRewrite>): String {
        var parts = UrlParts.of(link)
        val host = parts.host.removePrefix("www.")
        val youtube = host == "youtube.com" || host == "m.youtube.com" || host == "music.youtube.com"
        if (youtube && LinkRewrite.YOUTUBE_SHORTS_AS_VIDEOS in rewrites) {
            SHORTS_PATH.matchEntire(parts.path)?.let { m ->
                parts = parts.copy(path = "/watch", query = joinQuery("v=" + m.groupValues[1], parts.query))
            }
        }
        if (host == "music.youtube.com" && LinkRewrite.YOUTUBE_MUSIC_AS_YOUTUBE in rewrites &&
            (parts.path == "/watch" || parts.path == "/playlist")
        ) {
            parts = parts.copy(authority = "www.youtube.com")
        }
        val stillMusic = parts.authority.lowercase() == "music.youtube.com"
        if (youtube && !stillMusic && LinkRewrite.YOUTUBE_SHORT in rewrites) {
            val shorts = SHORTS_PATH.matchEntire(parts.path)?.groupValues?.get(1)
            val watched = if (parts.path == "/watch") queryValue(parts.query, "v") else null
            val id = (shorts ?: watched)?.takeIf { VIDEO_ID.matches(it) }
            if (id != null) {
                val rest = parts.query?.split('&')
                    ?.filter { it.isNotEmpty() && (shorts != null || it.substringBefore('=').lowercase() != "v") }
                    ?.joinToString("&")?.ifEmpty { null }
                parts = parts.copy(authority = "youtu.be", path = "/$id", query = rest)
            }
        }
        if (LinkRewrite.TWITTER_EMBEDS in rewrites && parts.path.length > 1) {
            when (host) {
                "twitter.com", "mobile.twitter.com" -> parts = parts.copy(authority = "fxtwitter.com")
                "x.com", "mobile.x.com" -> parts = parts.copy(authority = "fixupx.com")
            }
        }
        if (LinkRewrite.BLUESKY_EMBEDS in rewrites && host == "bsky.app" && parts.path.contains("/post/")) {
            parts = parts.copy(authority = "fxbsky.app")
        }
        if (LinkRewrite.READABLE in rewrites) {
            parts = parts.copy(
                path = readableLetters(parts.path),
                query = parts.query?.let { readableLetters(it) },
                fragment = parts.fragment?.let { readableLetters(it) },
            )
        }
        return parts.toString()
    }

    private val SHORTS_PATH = Regex("""/shorts/([A-Za-z0-9_-]+)/?""")

    /** A YouTube video's id: eleven of these, but nothing promises the count. */
    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{6,20}""")

    private fun joinQuery(first: String, rest: String?): String =
        if (rest.isNullOrEmpty()) first else "$first&$rest"

    private fun queryValue(query: String?, name: String): String? = query?.split('&')
        ?.firstOrNull { it.substringBefore('=').lowercase() == name }
        ?.substringAfter('=', "")
        ?.ifEmpty { null }

    /**
     * [text] with each `%`-escaped run of UTF-8 written as its letters, when
     * every character it decodes to is one somebody can see and that means
     * nothing to a link (a letter, a mark, a digit, a dash, a symbol). An
     * escaped ASCII character (`%20`, `%2F`, `%26`), a space of any kind, a
     * control or an invisible direction mark stays escaped, so the link opens
     * the same page and shows what it is.
     */
    internal fun readableLetters(text: String): String {
        if (!text.contains('%')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val bytes = java.io.ByteArrayOutputStream()
            var j = i
            while (j < text.length && text[j] == '%') {
                val byte = hexByte(text, j + 1) ?: break
                if (byte < 0x80) break
                bytes.write(byte)
                j += 3
            }
            if (j == i) {
                out.append(text[i])
                i++
                continue
            }
            val decoded = runCatching {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
            }.getOrNull()
            out.append(if (decoded != null && decoded.all { isShowable(it) }) decoded else text.substring(i, j))
            i = j
        }
        return out.toString()
    }

    private fun hexByte(text: String, at: Int): Int? {
        if (at + 1 >= text.length) return null
        val high = Character.digit(text[at], 16)
        val low = Character.digit(text[at + 1], 16)
        return if (high < 0 || low < 0) null else high * 16 + low
    }

    private fun isShowable(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR, Character.PRIVATE_USE, Character.UNASSIGNED,
        -> false
        // Lone halves are already refused by the decoder; a pair is an emoji or a rare letter.
        else -> true
    }

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
     * dial code when the selection or a mask supplies one, or, failing both,
     * the numbering plans of the phone's country (`PhoneNumbers.e164`), which
     * is what lets `wa.me` open a national number at all.
     */
    fun dialDigits(number: String, phoneFormats: List<String>): String {
        val digits = number.filter { it.isDigit() }
        if (number.trimStart().startsWith("+")) return digits
        val mask = PhoneFormats.parseAll(phoneFormats)
            .firstOrNull { PhoneFormats.matches(number, listOf(it)) }
        mask?.countryCode?.let { code -> return code + digits.takeLast(mask.national.length) }
        return com.wasimaster.wmkeyboard.core.phone.PhoneNumbers.e164(number)?.drop(1) ?: digits
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
        var hasHangul = false
        var hasHebrew = false
        var hasCyrillic = false
        var hasKanji = false
        var hasKana = false
        var hasArabicScript = false
        var hasPersianLetters = false
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
                        c in '\uAC00'..'\uD7A3' -> hasHangul = true
                        c in '\u05D0'..'\u05EA' -> hasHebrew = true
                        c in '\u0400'..'\u04FF' -> hasCyrillic = true
                        c in '\u4E00'..'\u9FFF' -> hasKanji = true
                        c in '\u3041'..'\u30FA' -> hasKana = true
                        c in '\u0620'..'\u065F' || c in '\u066E'..'\u06D3' -> {
                            hasArabicScript = true
                            if (c in "پچژگ") hasPersianLetters = true
                        }
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
        val hasTrackers = mayCarryTrackers(trimmed, options.links) && stripTrackersInText(trimmed, options.links) != null
        val lipiSource = if (options.scripts) ScriptConvert.detect(text, hasLatin) else null
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
            hasHangul = hasHangul,
            hasHebrew = hasHebrew,
            hasCyrillic = hasCyrillic,
            hasKanji = hasKanji,
            hasKana = hasKana,
            hasArabicScript = hasArabicScript,
            hasPersianLetters = hasPersianLetters,
            hasForeignDigits = hasForeignDigits,
            colour = colour,
            dateTime = dateTime,
            place = place,
            jsonShape = jsonShape,
            base64 = base64,
            urlEncoded = urlEncoded,
            hasTrackers = hasTrackers,
            lipiSource = lipiSource,
        )
    }
}

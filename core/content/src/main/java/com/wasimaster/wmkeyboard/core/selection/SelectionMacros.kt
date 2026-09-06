package com.wasimaster.wmkeyboard.core.selection

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats

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
    COPY,
    SHARE,
    /**
     * Rewrite the selection in place. What that means follows the kind: a phone
     * number takes the user's own mask, a link loses its tracking parameters, an
     * address is lower-cased, and plain text opens the case ladder below.
     */
    FORMAT,
    SEARCH,
    TRANSLATE,
    CALL,
    SMS,
    WHATSAPP,
    /** Compose a new message to the selected address. */
    EMAIL,
    /** Hand the selection to whichever app claims it: a browser, a mail client. */
    OPEN,
    /** Turn the link into a QR code with the generator tool. */
    QR,
    CASE_LOWER,
    CASE_TITLE,
    CASE_UPPER,
    CASE_SENTENCE,
    ;

    /** The chip's own word, and its accessibility label. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            COPY -> R.string.core_content_selection_macro_copy
            SHARE -> R.string.core_content_selection_macro_share
            FORMAT -> R.string.core_content_selection_macro_format
            SEARCH -> R.string.core_content_selection_macro_search
            TRANSLATE -> R.string.core_content_selection_macro_translate
            CALL -> R.string.core_content_selection_macro_call
            SMS -> R.string.core_content_selection_macro_sms
            WHATSAPP -> R.string.core_content_selection_macro_whatsapp
            EMAIL -> R.string.core_content_selection_macro_email
            OPEN -> R.string.core_content_selection_macro_open
            QR -> R.string.core_content_selection_macro_qr
            CASE_LOWER -> R.string.core_content_selection_macro_case_lower
            CASE_TITLE -> R.string.core_content_selection_macro_case_title
            CASE_UPPER -> R.string.core_content_selection_macro_case_upper
            CASE_SENTENCE -> R.string.core_content_selection_macro_case_sentence
        }

    /** Whether the macro leaves the keyboard for another app. */
    val leavesApp: Boolean
        get() = this == SHARE || this == CALL || this == SMS || this == WHATSAPP ||
            this == EMAIL || this == OPEN
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
     * Query parameters stripped by [formatUrl]: campaign trackers and the
     * per-click ids the big platforms staple on. Matched case-insensitively,
     * and `utm_*` by prefix, because the family keeps growing.
     */
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "mc_eid", "mc_cid",
        "igshid", "igsh", "ttclid", "twclid", "yclid", "si", "ref_src", "ref_url",
        "_openstat", "vero_id", "vero_conv", "oly_enc_id", "oly_anon_id", "spm",
    )

    private const val TRACKING_PREFIX = "utm_"

    /** The macros a settings screen can switch, in the order that screen lists them. */
    val configurable: List<SelectionMacro> = listOf(
        SelectionMacro.COPY,
        SelectionMacro.SHARE,
        SelectionMacro.FORMAT,
        SelectionMacro.SEARCH,
        SelectionMacro.TRANSLATE,
        SelectionMacro.CALL,
        SelectionMacro.SMS,
        SelectionMacro.WHATSAPP,
        SelectionMacro.EMAIL,
        SelectionMacro.OPEN,
        SelectionMacro.QR,
    )

    /**
     * The shipped set: everything the screenshot in the request names, plus the
     * message half of a selected phone number. Search and translate are off
     * until they are asked for, because both are a round trip to a network
     * service and neither is what a selection usually means.
     */
    val defaultMacros: Set<SelectionMacro> = setOf(
        SelectionMacro.COPY,
        SelectionMacro.SHARE,
        SelectionMacro.FORMAT,
        SelectionMacro.CALL,
        SelectionMacro.SMS,
        SelectionMacro.WHATSAPP,
        SelectionMacro.EMAIL,
        SelectionMacro.OPEN,
        SelectionMacro.QR,
    )

    /** The case ladder [SelectionMacro.FORMAT] opens on a plain-text selection. */
    val caseMacros: List<SelectionMacro> = listOf(
        SelectionMacro.CASE_LOWER,
        SelectionMacro.CASE_TITLE,
        SelectionMacro.CASE_UPPER,
        SelectionMacro.CASE_SENTENCE,
    )

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
        if (trimmed.length < MIN_LENGTH || trimmed.length > MAX_ENTITY_LENGTH) return SelectionKind.TEXT
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
     * The row for [kind], filtered to what [allowed] turns on and to the tools
     * that are actually available.
     *
     * [whatsAppInstalled] and [qrAvailable] are asked rather than assumed: a
     * chip that opens nothing is worse than no chip, and both answers live
     * outside this file (the package manager, the build flavour).
     *
     * [formattable] is whether [SelectionMacro.FORMAT] would change anything.
     * Plain text always answers yes, because the case ladder always has
     * somewhere to go; an entity answers no when it is already in its tidy
     * form, and the chip is then left off rather than drawn as a no-op.
     */
    fun offer(
        kind: SelectionKind,
        allowed: Set<SelectionMacro>,
        whatsAppInstalled: Boolean = true,
        qrAvailable: Boolean = true,
        formattable: Boolean = true,
    ): List<SelectionMacro> = macrosFor(kind).filter { macro ->
        macro in allowed &&
            (macro != SelectionMacro.WHATSAPP || whatsAppInstalled) &&
            (macro != SelectionMacro.QR || qrAvailable) &&
            (macro != SelectionMacro.FORMAT || formattable)
    }

    /**
     * Every macro [kind] can offer, in the order it reads on the bar.
     *
     * The generic pair leads on plain text and on a number, where copying is
     * the likeliest thing anybody wants; on a link or an address the action
     * that *uses* it leads instead, because a selection of one is nearly always
     * about going there.
     */
    fun macrosFor(kind: SelectionKind): List<SelectionMacro> = when (kind) {
        SelectionKind.PHONE -> listOf(
            SelectionMacro.COPY, SelectionMacro.SHARE, SelectionMacro.FORMAT,
            SelectionMacro.CALL, SelectionMacro.SMS, SelectionMacro.WHATSAPP,
        )
        SelectionKind.EMAIL -> listOf(
            SelectionMacro.EMAIL, SelectionMacro.COPY, SelectionMacro.OPEN,
            SelectionMacro.FORMAT, SelectionMacro.SHARE,
        )
        SelectionKind.URL -> listOf(
            SelectionMacro.OPEN, SelectionMacro.COPY, SelectionMacro.QR,
            SelectionMacro.FORMAT, SelectionMacro.SHARE,
        )
        SelectionKind.TEXT -> listOf(
            SelectionMacro.COPY, SelectionMacro.SHARE, SelectionMacro.FORMAT,
            SelectionMacro.SEARCH, SelectionMacro.TRANSLATE,
        )
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

    /**
     * [url] with its tracking parameters removed, and a scheme added when it
     * had none.
     *
     * Only the query is touched. Rewriting a path, a fragment or a host is how
     * a "clean this link" feature quietly breaks the link, and the parameters
     * are the part that is provably not about where the page is.
     */
    fun formatUrl(url: String): String? {
        val withScheme = if (url.contains("://")) url else "https://$url"
        val queryStart = withScheme.indexOf('?')
        if (queryStart < 0) return withScheme
        val fragmentStart = withScheme.indexOf('#', queryStart)
        val query = if (fragmentStart < 0) {
            withScheme.substring(queryStart + 1)
        } else {
            withScheme.substring(queryStart + 1, fragmentStart)
        }
        val fragment = if (fragmentStart < 0) "" else withScheme.substring(fragmentStart)
        val kept = query.split('&').filter { part ->
            val name = part.substringBefore('=').lowercase()
            name.isNotEmpty() && !name.startsWith(TRACKING_PREFIX) && name !in TRACKING_PARAMS
        }
        val base = withScheme.substring(0, queryStart)
        return if (kept.isEmpty()) base + fragment else "$base?${kept.joinToString("&")}$fragment"
    }

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
            SelectionMacro.CASE_LOWER -> selection.lowercase()
            SelectionMacro.CASE_UPPER -> selection.uppercase()
            SelectionMacro.CASE_TITLE -> recase(selection, everyWord = true, keepAcronyms = !allCaps)
            SelectionMacro.CASE_SENTENCE -> recase(selection, everyWord = false, keepAcronyms = !allCaps)
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
                    everyWord || startOfSentence -> word[0].uppercaseChar() + word.substring(1).lowercase()
                    else -> word.lowercase()
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
}

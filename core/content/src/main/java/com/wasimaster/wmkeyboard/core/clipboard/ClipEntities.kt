package com.wasimaster.wmkeyboard.core.clipboard

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.location.LocationParser
import com.wasimaster.wmkeyboard.core.location.ParsedLocation

/**
 * A kind of fragment worth pulling out of a clip on its own: the six digits
 * buried in a verification SMS, the number in "call me on …", the link inside a
 * sentence. Ordered by how time-critical the fragment usually is — the panel
 * lists chips in this order.
 */
enum class ClipEntityKind {
    OTP,
    PHONE,

    /** An email address (gap list item 26), which a held clip offers to write to. */
    EMAIL,
    URL,

    /**
     * Coordinates, a Plus code or a `geo:` link (#350). A map link stays a
     * [URL]: it is one, and the Location tool's Found tab reads both.
     */
    PLACE;

    /** Two-word noun for the info popup and the chip's accessibility label. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            OTP -> R.string.core_content_clip_entity_otp_label
            PHONE -> R.string.core_content_clip_entity_phone_label
            EMAIL -> R.string.core_content_clip_entity_email_label
            URL -> R.string.core_content_clip_entity_url_label
            PLACE -> R.string.core_content_clip_entity_place_label
        }

    /** All-caps tag printed on the chip itself. */
    @get:StringRes
    val tagRes: Int
        get() = when (this) {
            OTP -> R.string.core_content_clip_entity_otp_tag
            PHONE -> R.string.core_content_clip_entity_phone_tag
            EMAIL -> R.string.core_content_clip_entity_email_tag
            URL -> R.string.core_content_clip_entity_url_tag
            PLACE -> R.string.core_content_clip_entity_place_tag
        }
}

/**
 * A span inside one clip's text that can be pasted by itself.
 *
 * [start]/[end] index into [sourceText] (the scanned prefix of the clip, not
 * necessarily the whole clip) so the UI can show the fragment highlighted in
 * its surroundings — the point being that a chip is explicitly *part of* an
 * entry, never an entry of its own.
 */
data class ClipEntity(
    val kind: ClipEntityKind,
    val value: String,
    /** [ClipItem.id] of the clip this was found in. */
    val sourceId: Long,
    val sourceText: String,
    val start: Int,
    val end: Int,
) {
    /** Identity for de-duplicating the same fragment found in several clips. */
    val key: String get() = "${kind.name}:$value"
}

/**
 * Pulls one-time codes, phone numbers, email addresses and links out of
 * clipboard text.
 *
 * Everything here is deliberately conservative in one direction and loose in
 * the other: a missed fragment costs the user a manual retype, a wrong one
 * costs a chip they ignore. So the rules lean towards offering, except where a
 * pattern is a known impostor (dates, order numbers, ZIP codes), which get
 * explicit guards.
 *
 * Detection order matters — URLs are claimed first because their digits would
 * otherwise read as a phone number, then keyword-anchored codes, then phone
 * numbers, which have the loosest shape. Overlapping candidates lose.
 */
object ClipEntities {

    /** Clips scanned per refresh, newest first; older history is stale anyway. */
    private const val MAX_SCANNED_CLIPS = 40

    /** Characters scanned per clip — a pasted article carries its useful bits early. */
    private const val MAX_SCANNED_CHARS = 4000

    /** Chips the panel will show at once. */
    private const val MAX_ENTITIES = 12

    /** How far from its keyword a code may sit and still count as that code. */
    private const val OTP_KEYWORD_RANGE = 40

    private val URL = Regex(
        """(?:https?://|www\.)[^\s<>"'`]{2,}""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * An address with no `https://` or `www.` in front (#612): `x.com`,
     * `m.youtube.com/watch?v=…`. Not part of an email address, a longer word
     * or a path, and only with a known ending; see [ClipLinks.BARE_TLDS].
     */
    private val BARE_URL = Regex(
        """(?<![\w@./-])${ClipLinks.BARE_HOST}(?![\w@-])(?:[/?#][^\s<>"'`]*)?""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * `name@example.com`, ASCII only, with a dotted domain that ends in two or
     * more letters. The guards keep it from starting inside a longer run of
     * address characters or ending halfway through a domain label.
     */
    private val EMAIL = Regex("""(?<![\w.%+-])[A-Za-z0-9][A-Za-z0-9._%+-]{0,63}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?)*\.[A-Za-z]{2,24}(?![\w-])""")

    /**
     * The first email address in [text], or null: what a held clip's Compose
     * email writes to. The whole clip counts here, unlike in [entitiesIn],
     * since the action is the point rather than a chip.
     */
    fun emailIn(text: String): String? = EMAIL.find(text.take(MAX_SCANNED_CHARS))?.value

    // The word-character guards keep digit runs glued to an identifier out —
    // `emoji_864356927` is a filename's tail, not a number to dial.
    private val PHONE = Regex("""(?<![\w+])\+?\d[\d ().\-]{5,18}\d(?!\w)""")

    /**
     * `2024-05-12`, `12/05/24` — right digit count, wrong meaning. Pinned to
     * the two group shapes dates actually come in (`yyyy-m-d` and `d-m-yy`) so
     * a hyphenated phone number like `555-12-3456` is not mistaken for one.
     */
    private val DATE = Regex("""\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}""")

    /** Separators people write phone numbers with. */
    private const val PHONE_SEPARATORS = " -()."

    /**
     * The words Chinese, Japanese and Korean senders put beside a code (#992).
     * A separate alternation because `\b` means nothing next to Han, kana or
     * Hangul, so these sit outside the Latin group's word boundaries. Shared
     * with the notification extractor's anchored pattern.
     */
    internal const val CJK_OTP_KEYWORDS =
        "验证码|驗證碼|校验码|校驗碼|动态码|動態碼|动态密码|動態密碼|认证码|認證碼|" +
            "确认码|確認碼|短信码|激活码|安全码|安全碼|登录码|登錄碼|口令|" +
            "認証コード|確認コード|認証番号|確認番号|ワンタイムパスワード|セキュリティコード|" +
            "인증번호|인증 번호|인증코드|확인코드|확인 코드|보안코드|승인번호"

    private val OTP_KEYWORD = Regex(
        "\\b(?:otp|one[- ]?time(?: password| passcode| code)?|passcode|pass code|" +
            "verification|verify|confirmation|security code|auth(?:entication)? code|" +
            "access code|login code|code|pin|2fa)\\b|$CJK_OTP_KEYWORDS",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The user's own code words (#992), from Settings › Typing › One-time
     * codes, for wordings no built-in list knows. Matched by plain
     * containment, never `\b`, which does not exist next to Han, Thai or
     * Khmer. Set by whoever owns the setting; null when there are none.
     */
    @Volatile
    private var userOtpKeyword: Regex? = null

    /** Replaces the user's code words; blank entries are dropped. */
    fun setUserOtpKeywords(words: List<String>) {
        val kept = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        userOtpKeyword = if (kept.isEmpty()) {
            null
        } else {
            // Longest first, so "动态密码" is not cut short by a "动态" also listed.
            Regex(kept.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        }
    }

    /** [setUserOtpKeywords] from the setting as typed: one list, commas or new lines between. */
    fun parseOtpKeywords(raw: String): List<String> =
        raw.split(',', '，', '、', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Words that turn a nearby "code" into something that is not a login code.
     * Checked against the word immediately before the keyword.
     */
    private val OTP_KEYWORD_DENY = setOf(
        "zip", "postal", "post", "area", "country", "dial", "colour", "color",
        "hex", "sort", "swift", "ifsc", "iban", "bar", "qr", "source", "error",
    )

    // ASCII classes rather than `\w`: Android's regex counts Han as a word
    // character, and a code glued to its keyword ("验证码467321") then had no
    // start (#992).
    // Either side of a decimal point or thousands comma is part of an amount:
    // `OTP for Rs 1500.00 is 482913` (#1049).
    private val OTP_TOKEN = Regex("""(?<![A-Za-z0-9_-])(?<!\d[.,])(?:[A-Za-z]-)?[A-Z0-9]{4,8}(?![A-Za-z0-9_-])(?![.,]\d)""")

    /**
     * Every fragment in [text], with [sourceId] stamped on each. Ranges never
     * overlap; the list is in detection order (URLs, codes, phone numbers).
     *
     * [phoneFormats] narrows the phone pass to the shapes the user says they
     * copy (see [PhoneFormats]). An empty list keeps the shape-only rules,
     * which is what everyone gets until they set a format.
     */
    fun extract(
        text: String,
        sourceId: Long = 0L,
        phoneFormats: List<PhoneMask> = emptyList(),
    ): List<ClipEntity> {
        // Half-open [start, end) spans already spoken for by an earlier, more
        // certain kind.
        val claimed = ArrayList<Pair<Int, Int>>()
        val found = ArrayList<ClipEntity>()

        fun claim(kind: ClipEntityKind, value: String, start: Int) {
            if (value.isEmpty()) return
            val end = start + value.length
            if (claimed.any { (from, to) -> start < to && from < end }) return
            claimed += start to end
            found += ClipEntity(kind, value, sourceId, text, start, end)
        }

        for (match in URL.findAll(text)) {
            val url = trimUrlTail(match.value)
            // "www.x" and the like are noise; a real link has a dot and a TLD.
            if (url.length >= 8 && url.substringAfterLast('.').isNotBlank()) {
                claim(ClipEntityKind.URL, url, match.range.first)
            }
        }
        // After the links with a scheme, so `https://me@host/` stays a link,
        // and before the bare ones, which would otherwise take the domain.
        for (match in EMAIL.findAll(text)) {
            claim(ClipEntityKind.EMAIL, match.value, match.range.first)
        }
        // After the ones with a scheme, which claim their own host first.
        for (match in BARE_URL.findAll(text)) {
            claim(ClipEntityKind.URL, trimUrlTail(match.value), match.range.first)
        }

        // Before codes and numbers: a Plus code is code-shaped, and the
        // digits of a coordinate pair could read as either.
        for (place in LocationParser.findAll(text)) {
            if (place.kind != ParsedLocation.Kind.LINK) claim(ClipEntityKind.PLACE, place.text, place.start)
        }

        for ((start, value) in otpTokens(text)) {
            claim(ClipEntityKind.OTP, value, start)
        }

        for (match in PHONE.findAll(text)) {
            if (isDiallable(match.value, text, match.range.first, phoneFormats)) {
                claim(ClipEntityKind.PHONE, match.value, match.range.first)
            }
        }

        return found
    }

    /**
     * Fragments across [items], de-duplicated and capped for the panel.
     *
     * A fragment that *is* the whole clip is dropped: that clip's own card
     * already pastes it, and a chip for it would break the promise the section
     * header makes — chips are parts of entries, not entries.
     */
    fun entitiesIn(
        items: List<ClipItem>,
        phoneFormats: List<PhoneMask> = emptyList(),
    ): List<ClipEntity> {
        val seen = HashSet<String>()
        val found = ArrayList<ClipEntity>()
        for (item in items.take(MAX_SCANNED_CLIPS)) {
            if (!item.kind.isTextual) continue
            // A clip whose card is masked must not have its contents lifted out
            // and printed on a chip beside it.
            if (item.sensitive) continue
            val text = item.text.take(MAX_SCANNED_CHARS)
            val whole = text.trim()
            for (entity in extract(text, item.id, phoneFormats)) {
                if (entity.value == whole) continue
                if (seen.add(entity.key)) found += entity
            }
        }
        // Stable sort: codes first, then phones, then links, each still in
        // newest-clip-first order because that is how [items] arrives.
        return found.sortedBy { it.kind.ordinal }.take(MAX_ENTITIES)
    }

    /**
     * Each clip's own fragments, by [ClipItem.id] (#472): what the clip cards
     * count in their corner and offer under Extract. The same rules as
     * [entitiesIn] (no masked clip, nothing that is the whole clip), but per
     * clip and uncapped across clips, since every card answers for itself. A
     * fragment repeated inside one clip is listed once.
     */
    fun byClip(
        items: List<ClipItem>,
        phoneFormats: List<PhoneMask> = emptyList(),
    ): Map<Long, List<ClipEntity>> {
        val out = HashMap<Long, List<ClipEntity>>()
        for (item in items) {
            if (!item.kind.isTextual || item.sensitive) continue
            val text = item.text.take(MAX_SCANNED_CHARS)
            val whole = text.trim()
            val found = extract(text, item.id, phoneFormats)
                .filter { it.value != whole }
                .distinctBy { it.key }
                .sortedBy { it.kind.ordinal }
            if (found.isNotEmpty()) out[item.id] = found
        }
        return out
    }

    /** Codes sitting close enough to a code-ish word to be that code. */
    private fun otpTokens(text: String): List<Pair<Int, String>> {
        // Half-open (start, end) spans, so a token butting straight up against
        // a keyword measures a gap of zero rather than minus one.
        val builtIn = OTP_KEYWORD.findAll(text)
            .filter { !isDeniedKeyword(text, it.range.first) }
            .map { it.range.first to it.range.last + 1 }
        // The user's words skip the deny list: they named the word themselves.
        val own = userOtpKeyword?.findAll(text).orEmpty().map { it.range.first to it.range.last + 1 }
        val keywords = (builtIn + own).toList()
        if (keywords.isEmpty()) return emptyList()
        return OTP_TOKEN.findAll(text)
            .filter { isCodeShaped(it.value) }
            .filter { token ->
                val start = token.range.first
                val end = token.range.last + 1
                keywords.any { (keyStart, keyEnd) ->
                    val gap = when {
                        start >= keyEnd -> start - keyEnd
                        end <= keyStart -> keyStart - end
                        // The token *is* the keyword ("PIN" has no digits, but
                        // an alphanumeric keyword could collide).
                        else -> return@any false
                    }
                    gap <= OTP_KEYWORD_RANGE
                }
            }
            // Google's `G-123456` prefix is branding, not part of the code —
            // pasting it into a six-box OTP field would be wrong.
            .map { token ->
                val body = token.value.substringAfterLast('-')
                token.range.first + (token.value.length - body.length) to body
            }
            .toList()
    }

    /**
     * True when the word before the keyword at [start] disqualifies it.
     * Internal because the notification-code extractor applies the same rule
     * to its own keyword matches.
     */
    internal fun isDeniedKeyword(text: String, start: Int): Boolean {
        val before = text.take(start).trimEnd()
        val word = before.takeLastWhile { it.isLetter() }
        return word.isNotEmpty() && word.lowercase() in OTP_KEYWORD_DENY
    }

    /**
     * A code has digits. Letters are allowed (Google's `G-123456`, alphanumeric
     * codes) but only alongside at least two digits, so ordinary shouty words
     * like `PLEASE` or `HTTPS` never qualify.
     */
    private fun isCodeShaped(token: String): Boolean {
        val body = token.substringAfterLast('-')
        val digits = body.count { it.isDigit() }
        if (digits == 0) return false
        if (body.any { it.isLetter() } && digits < 2) return false
        // A bare four-digit year in prose is almost never the code.
        if (body.length == 4 && digits == 4 && body.toInt() in 1900..2099) return false
        return true
    }

    /**
     * Whether a digit run reads as a number you would dial. Guards the two
     * impostors that share its shape: dates, and the long unpunctuated order or
     * account numbers that follow a `#`.
     *
     * With [phoneFormats] set, the user has said what their numbers look like,
     * so the shape rules that stand in for that knowledge step aside and the
     * masks decide the length. The impostor guards stay either way: a mask can
     * only take numbers away, never add one back.
     */
    private fun isDiallable(
        raw: String,
        text: String,
        start: Int,
        phoneFormats: List<PhoneMask>,
    ): Boolean {
        val digits = raw.count { it.isDigit() }
        val floor = if (phoneFormats.isEmpty()) 7 else 4
        if (digits !in floor..15) return false
        // Whole-match, not "contains": a date buried in a longer run of digits
        // is a coincidence, while a match that is the entire clip fragment is
        // the date itself.
        if (DATE.matches(raw.trim())) return false
        val previous = text.getOrNull(start - 1)
        if (previous == '#' || previous == '$' || previous == '£' || previous == '€') return false
        if (phoneFormats.isNotEmpty()) return PhoneFormats.matches(raw, phoneFormats)
        val punctuated = raw.any { it in PHONE_SEPARATORS }
        // Unpunctuated and unprefixed: only lengths people actually dial.
        if (!punctuated && !raw.startsWith("+") && digits > 11) return false
        return true
    }

    /**
     * Drops the sentence punctuation a URL match swallows. A closing bracket
     * only goes if nothing opened it, so `…/wiki/Foo_(bar)` survives.
     */
    private fun trimUrlTail(raw: String): String {
        var url = raw.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"', '”', '’')
        while (url.isNotEmpty()) {
            val last = url.last()
            val open = when (last) {
                ')' -> '('
                ']' -> '['
                '}' -> '{'
                else -> break
            }
            if (url.count { it == open } >= url.count { it == last }) break
            url = url.dropLast(1).trimEnd('.', ',', ';', ':', '!', '?')
        }
        return url
    }
}

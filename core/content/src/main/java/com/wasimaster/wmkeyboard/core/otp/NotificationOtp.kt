package com.wasimaster.wmkeyboard.core.otp

import android.content.Context
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntities
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntityKind
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A one-time code lifted out of a just-arrived notification, ready to be
 * offered as a chip on the suggestion strip.
 *
 * Never persisted anywhere: it lives in [NotificationOtpBus] until it is used,
 * dismissed, replaced by a newer code, or ages out. A verification code is
 * secret for the few minutes it is valid, and the way to keep a secret is to
 * not write it down.
 */
data class NotificationOtp(
    /** The code itself, exactly as it should be typed into the field. */
    val code: String,
    /** Package that posted the notification. */
    val sourcePackage: String,
    /** That package's launcher label, for the chip and its accessibility text. */
    val sourceApp: String,
    /**
     * The notification text the code was found in, capped — shown nowhere yet,
     * carried so a future press-and-hold popup can show the code in context
     * the way the clipboard fragment chips do.
     */
    val message: String,
    /**
     * [android.service.notification.StatusBarNotification.getKey], kept so the
     * "clear the notification" option can cancel exactly this notification
     * once the code has been used. Null when the poster is unknown.
     */
    val notificationKey: String?,
    /** [android.service.notification.StatusBarNotification.getPostTime]. */
    val postedAt: Long,
    /** When an authenticator's code stops working. A notification's has no known end. */
    val validUntil: Long? = null,
)

/**
 * The newest code found in a notification, or null. One slot, newest wins: two
 * codes in flight means the older one was a mistyped phone number or an
 * abandoned login, and offering a stale code loses to offering none.
 *
 * A plain in-process singleton rather than anything grander because the
 * notification listener and the IME service live in the same process — the
 * code never needs to cross a boundary, which is exactly how it avoids ever
 * being written down.
 */
object NotificationOtpBus {

    private val _latest = MutableStateFlow<NotificationOtp?>(null)

    /** Newest unconsumed code; the IME collects this to raise its chip. */
    val latest: StateFlow<NotificationOtp?> = _latest.asStateFlow()

    /**
     * Codes already seen, keyed by app and code, with when each was first
     * seen. An app that re-posts its notification (a new message in the same
     * thread, a re-sort) hands the listener a fresh post time, so without this
     * the same code came straight back after it was used or closed, and its
     * expiry clock restarted with every re-post: one chip stayed up for a
     * quarter of an hour. Codes only, never written down, oldest dropped.
     */
    private val firstSeen = object : LinkedHashMap<String, Long>(32, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > 32
    }

    /** The subset of [firstSeen] that was used, closed or aged out. */
    private val retired = HashSet<String>()

    private fun keyOf(otp: NotificationOtp) = "${otp.sourcePackage}\u0000${otp.code}"

    fun publish(otp: NotificationOtp) {
        val key = keyOf(otp)
        val seen = synchronized(firstSeen) {
            if (key in retired) return
            firstSeen.getOrPut(key) { otp.postedAt }.also { retired.retainAll(firstSeen.keys) }
        }
        // Newest wins by when a code arrived, not by when it was read: a group
        // summary or a scan of the shade can hand over an older code late.
        val current = _latest.value
        if (current != null && keyOf(current) != key && seen < current.postedAt) return
        _latest.value = if (seen < otp.postedAt) otp.copy(postedAt = seen) else otp
    }

    /** The code was used, dismissed, or expired — drop it everywhere at once. */
    fun clear() {
        _latest.value?.let { otp ->
            val key = keyOf(otp)
            synchronized(firstSeen) {
                firstSeen.getOrPut(key) { otp.postedAt }
                retired.add(key)
            }
        }
        _latest.value = null
    }
}

/**
 * Whether the notification listener may read notification text at all.
 *
 * The listener lives in a module that cannot see the settings repository, and
 * it has to answer this before the user's first unlock — so the flag is
 * mirrored into device-protected SharedPreferences by whoever owns the real
 * setting, and cached because the question is asked once per notification on
 * the main thread.
 */
object NotificationOtpCapture {

    private const val PREFS = "otp_capture"
    private const val KEY_ENABLED = "enabled"

    @Volatile
    private var cached: Boolean? = null

    /** When Android last handed the listener a notification with its text hidden, or 0. */
    fun lastRedactedAt(context: Context): Long = prefs(context).getLong("redacted_at", 0L)

    internal fun noteRedacted(context: Context, at: Long) {
        prefs(context).edit().putLong("redacted_at", at).apply()
    }

    fun isEnabled(context: Context): Boolean =
        cached ?: prefs(context).getBoolean(KEY_ENABLED, false).also { cached = it }

    fun setEnabled(context: Context, value: Boolean) {
        cached = value
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        // Turning the feature off must also drop a code already captured —
        // holding it would be exactly the reading the user just opted out of.
        if (!value) NotificationOtpBus.clear()
    }

    private const val KEY_KEYWORDS = "keywords"

    @Volatile
    private var keywordsLoaded = false

    /**
     * Hands the user's own code words (#992) to [ClipEntities] the first time
     * a notification is read, for a listener that starts before the keyboard
     * has mirrored them this run.
     */
    fun loadKeywords(context: Context) {
        if (keywordsLoaded) return
        keywordsLoaded = true
        val raw = prefs(context).getString(KEY_KEYWORDS, null).orEmpty()
        ClipEntities.setUserOtpKeywords(ClipEntities.parseOtpKeywords(raw))
    }

    /** Mirrors the user's code words, [raw] as typed in settings. */
    fun setKeywords(context: Context, raw: String) {
        keywordsLoaded = true
        ClipEntities.setUserOtpKeywords(ClipEntities.parseOtpKeywords(raw))
        prefs(context).edit().putString(KEY_KEYWORDS, raw).apply()
    }

    private fun prefs(context: Context) =
        DirectBoot.deviceContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Finds the one-time code in a notification's text, or decides there is none.
 *
 * Same posture as [ClipEntities]: a missed code costs a retype, a wrong one
 * costs a chip the user ignores — but a notification chip is pushier than a
 * clipboard chip (it appears unbidden over the keys), so every pattern here is
 * anchored to an explicit code word. There is no "any six digits" tier: a
 * delivery slot, an order number and a flight time all arrive as notifications
 * too.
 *
 * Two passes. The anchored patterns pin the code to its keyword ("is your
 * code", "code:", "use ... to sign in") and run in precision order, so a
 * message with several numbers ("OTP for account 2310990533 is 4279") yields
 * the code and not the account. When none of them bites, the clipboard
 * detector's proximity pass runs as the recall net — it also handles the
 * alphanumeric codes ("WX8Q2P") the anchored digit patterns leave alone.
 */
object NotificationOtpExtractor {

    /** Words that promise the number beside them is a login code. */
    private const val KEYWORDS =
        "(?:otp|one[- ]?time\\s+(?:password|passcode|code)|passcode|pass\\s+code|" +
            "(?:verification|security|authentication|authorization|access|login|" +
            "sign[- ]?in|confirmation|activation|2fa)\\s+(?:code|pin)|" +
            "verification|code|pin)"

    /** `123456`, `123 456`, `123-456` — separators stripped before use. */
    private const val CODE = "(\\d{3}[- ]?\\d{3}|\\d{4,8})"

    /** Google's `G-123456` style branding prefix, not part of the code. */
    private const val BRAND_PREFIX = "(?:[A-Za-z]-)?"

    /**
     * Precision order: the first pattern to produce a valid code wins, so the
     * shapes that can only be a code come before the looser ones.
     */
    private val ANCHORED = listOf(
        // "482913 is your Amazon OTP", "4279 is the verification code for …"
        Regex(
            "\\b$BRAND_PREFIX$CODE\\s+is\\s+(?:your|the)?\\s*(?:[\\w.-]+\\s+)?$KEYWORDS",
            RegexOption.IGNORE_CASE,
        ),
        // "Your OTP is 482913", "code: 482913", "PIN - 4829"
        Regex(
            "$KEYWORDS\\s*(?:is|:|-|=)\\s*$BRAND_PREFIX$CODE\\b",
            RegexOption.IGNORE_CASE,
        ),
        // "OTP for account 2310990533 is 4279" — keyword, a short stretch of
        // anything-but-sentence-end, then "is" and the code.
        Regex(
            "$KEYWORDS\\b[^.!\\n]{0,40}?\\bis\\s+$BRAND_PREFIX$CODE\\b",
            RegexOption.IGNORE_CASE,
        ),
        // "use 482913 to sign in", "enter 4829 to verify"
        Regex(
            "\\b(?:use|enter|type)\\s+(?:code\\s+)?$BRAND_PREFIX$CODE\\b",
            RegexOption.IGNORE_CASE,
        ),
        // "当前验证码为 467321", "認証コードは123456です", "인증번호 [123456]"
        // (#992): the keyword, a few joining characters that are not part of
        // a code, then the code. ASCII guards, since `\b` means nothing next
        // to Han and Android's `\w` matches it.
        Regex(
            "(?:${ClipEntities.CJK_OTP_KEYWORDS})[^0-9A-Za-z\\n]{0,6}?(?<![0-9A-Za-z])$CODE(?![0-9A-Za-z])",
        ),
    )

    fun extract(text: String): String? {
        if (text.isBlank()) return null
        for (pattern in ANCHORED) {
            for (match in pattern.findAll(text)) {
                if (keywordDenied(text, match.range.first)) continue
                val code = clean(match.groupValues[1])
                if (isPlausibleCode(code)) return code
            }
        }
        // Recall net: the clipboard detector's keyword-proximity pass, which
        // shares its deny list, year guard and alphanumeric handling.
        return ClipEntities.extract(text)
            .firstOrNull { it.kind == ClipEntityKind.OTP }
            ?.value
    }

    private fun clean(raw: String): String = raw.replace(Regex("[- ]"), "")

    private fun isPlausibleCode(code: String): Boolean {
        if (code.length !in 4..8) return false
        // A bare year reads as a code to a regex and as a date to a person.
        if (code.length == 4 && code.toInt() in 1900..2099) return false
        return true
    }

    /**
     * The word just before the match turns its keyword into something else —
     * "zip code 12345" must stay a ZIP. Same rule and list as the clipboard
     * detector's, applied to the anchored patterns' own matches.
     */
    private fun keywordDenied(text: String, matchStart: Int): Boolean =
        ClipEntities.isDeniedKeyword(text, matchStart)
}

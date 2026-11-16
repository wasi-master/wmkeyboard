package com.wasimaster.wmkeyboard.ime

/**
 * Whether the focused field is a box a verification code is meant to go into.
 *
 * The one-time-code chip and the copied-code paste chip both have a setting
 * that narrows them to code boxes, and for a long time "code box" meant
 * `TYPE_CLASS_NUMBER` and nothing else. That is the shape of *most* code
 * boxes and it silently hid the chip in the rest: a web form's
 * `<input type="text" autocomplete="one-time-code">` reports plain text, and
 * so does every app that styles its own six-character field. The chip never
 * appeared where the code was wanted and then turned up later in some
 * unrelated number field, which reads as the feature being broken rather than
 * as a setting doing its job.
 *
 * So the test is widened to the other thing a code box always has: it says so.
 * The app names the field — through the hint the box shows while empty, the
 * label the accessibility layer reads, or the resource id the framework passes
 * along — and those names say "OTP", "verification code", "passcode". Matching
 * on that costs nothing (three short strings, once per focus) and catches the
 * text-input code boxes that the input type alone cannot.
 *
 * Everything here is a pure function of strings so the word lists can be
 * tested without a running IME.
 */

/** Words that, standing alone in a field's name, mark it as a code box. */
private val CODE_WORDS = setOf(
    "otp", "otc", "code", "codes", "passcode", "pin",
    "verification", "verify", "authentication", "2fa", "mfa",
)

/**
 * Words that turn a following "code" into something else entirely. A postal
 * code, a promo code and a dialling code are all code boxes by name and none
 * of them wants a verification code typed into it. Same rule, and the first
 * half of the same list, as the clipboard detector's prose deny list — the
 * extra entries are the ones that only ever show up as a field label.
 */
private val CODE_DENY = setOf(
    "zip", "postal", "post", "area", "country", "dial", "colour", "color",
    "hex", "sort", "swift", "ifsc", "iban", "bar", "qr", "source", "error",
    "promo", "coupon", "referral", "invite", "discount", "voucher", "gift",
    "product", "currency", "language", "branch", "airport", "state",
)

/**
 * Whether [fieldKind] and the names the app gave the field add up to a code
 * box.
 *
 * @param fieldKind the focused field's input class. A number field is a code
 * box on its shape alone — that was the whole of the old test and it stays.
 * @param names the field's own words: its hint, its accessibility label, its
 * resource id. Nulls and blanks are skipped.
 */
internal fun looksLikeCodeField(fieldKind: FieldKind, vararg names: String?): Boolean {
    if (fieldKind == FieldKind.NUMBER) return true
    return names.any { it != null && namesACodeBox(it) }
}

/**
 * Whether one field name reads as a code box.
 *
 * Split on everything that is not a letter or digit *and* at camel-case humps,
 * so `otpInput`, `com.bank:id/verification_code` and "Enter OTP" all come
 * apart into the same kind of word list.
 */
private fun namesACodeBox(name: String): Boolean {
    val words = splitWords(name)
    words.forEachIndexed { i, word ->
        // "one-time code", "onetime pin" — the qualifier carries the meaning,
        // and a deny word can never precede it.
        if (word == "onetime") return true
        if (word == "one" && words.getOrNull(i + 1) == "time") return true
        if (word !in CODE_WORDS) return@forEachIndexed
        // Only "code" is ambiguous enough to need the deny list; "otp" and
        // "passcode" are never anything else.
        if (word == "code" || word == "codes") {
            if (words.getOrNull(i - 1) in CODE_DENY) return@forEachIndexed
        }
        return true
    }
    return false
}

/** `verification_code`, `otpInput`, `Enter OTP` → `[verification, code]`, … */
private fun splitWords(name: String): List<String> {
    val out = ArrayList<String>(6)
    val word = StringBuilder()
    fun flush() {
        if (word.isNotEmpty()) {
            out += word.toString().lowercase()
            word.setLength(0)
        }
    }
    for ((i, c) in name.withIndex()) {
        // A capital after a lower-case letter starts a new word (`otpCode`),
        // and so does the last capital of a run before a lower-case one
        // (`OTPField` → `OTP`, `Field`). Anything else keeps a run of capitals
        // together, so `OTP` and `2FA` stay one word each.
        val startsWord = c.isUpperCase() && i > 0 && (
            name[i - 1].isLowerCase() ||
                (name[i - 1].isUpperCase() && name.getOrNull(i + 1)?.isLowerCase() == true)
            )
        when {
            !c.isLetterOrDigit() -> flush()
            startsWord -> {
                flush()
                word.append(c)
            }
            else -> word.append(c)
        }
    }
    flush()
    return out
}

/**
 * Words that, standing alone in a field's name, mark it as a password or PIN
 * box (#1404). Many banking apps and web PIN inputs draw dots without telling
 * the keyboard the field is a password, so the input type alone reads them as
 * a plain number or text box.
 */
private val SECRET_WORDS = setOf(
    "password", "passwd", "pwd", "passphrase", "passcode", "pin", "pincode", "mpin", "tpin",
    "cvv", "cvc", "cvv2", "secret",
    // The same word as apps in a few other languages name the box.
    "passwort", "kennwort", "contraseña", "contrasena", "senha", "motdepasse", "wachtwoord",
    "hasło", "haslo", "lösenord", "losenord", "adgangskode", "salasana", "parola", "şifre", "sifre",
    "пароль",
)

/**
 * The same, for scripts written without spaces between words, or where the
 * name is a phrase: matched anywhere in the name, since "请输入密码" is one run
 * of letters to [splitWords].
 */
private val SECRET_PHRASES = listOf(
    "密码", "密碼", "パスワード", "暗証番号", "비밀번호", "पासवर्ड", "পাসওয়ার্ড", "كلمة المرور", "كلمة السر", "رمز المرور",
)

/**
 * Whether the names the app gave the field say it takes a password or a PIN
 * (#1404), whatever its input type says. Pure, like [looksLikeCodeField].
 */
internal fun looksLikeSecretField(vararg names: String?): Boolean =
    names.any { name ->
        if (name.isNullOrBlank()) return@any false
        if (SECRET_PHRASES.any { it in name }) return@any true
        val words = splitWords(name)
        // "Mot de passe" and "security code" are two or three words in the name.
        words.any { it in SECRET_WORDS } ||
            words.zipWithNext().any { (a, b) -> (a == "mot" && b == "de") || (a == "security" && b == "code") }
    }

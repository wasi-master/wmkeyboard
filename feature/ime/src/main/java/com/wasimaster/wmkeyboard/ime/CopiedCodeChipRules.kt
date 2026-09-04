package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.CopiedCodeChip

/**
 * Whether a code-shaped clip may take the suggestion strip's paste chip.
 *
 * Split out of [WMKeyboardService.maybeShowCopiedCodeSuggestion] because these
 * three rules are the whole of the policy and the rest of that method is state
 * the decision needs rather than decisions of its own. Everything here is a
 * pure function of numbers, so the freshness window and the replacement rule
 * can be tested without a running IME.
 *
 * The caller still owns the checks that are not policy: the clip has to *be* a
 * bare code, it must not be the one already pasted, and incognito and a
 * mid-entry code both silence the strip outright.
 *
 * @param showingTimestamp when the chip currently on the strip was copied, or
 * null when the strip has none.
 */
internal fun offersCopiedCode(
    mode: CopiedCodeChip,
    fieldKind: FieldKind,
    clipTimestamp: Long,
    showingTimestamp: Long?,
    now: Long,
    maxAgeMs: Long,
): Boolean {
    when (mode) {
        CopiedCodeChip.OFF -> return false
        // The same field shape the notification chip reads as a code box, and
        // deliberately no wider: a phone or date field asks for digits too, and
        // neither is a reason to put a code on the strip when the user asked
        // for code boxes only.
        CopiedCodeChip.CODE_FIELDS -> if (fieldKind != FieldKind.NUMBER) return false
        CopiedCodeChip.ANY_FIELD -> Unit
    }
    // A code goes stale fast, and one copied yesterday resurfacing on a login
    // screen is noise at best and the wrong code at worst.
    if (now - clipTimestamp > maxAgeMs) return false
    // A chip already up wins only while it is the fresher offer. Comparing
    // timestamps rather than testing for null is what makes the copy listener's
    // call work: re-copying just the code out of a message copied a moment ago
    // leaves that message's chip on the strip, and the code — the thing the
    // user went back for — would never replace it.
    return showingTimestamp == null || showingTimestamp < clipTimestamp
}

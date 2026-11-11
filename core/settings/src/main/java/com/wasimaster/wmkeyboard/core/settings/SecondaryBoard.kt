package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.layout.resolveLayoutLanguage

/**
 * The layout pinned on the Secondary layout tool when it is a whole board
 * rather than a secondary grid (#1230): an enabled language layout, shipped
 * or the user's own, that the tool swaps in as the active board, so its
 * dictionary, composer and autocorrect come with it. Null when the pin names
 * a secondary grid (shown over the board, as it always was), nothing, or a
 * layout that is no longer enabled.
 *
 * [KeyboardSettings.enabledLayoutIds] never holds a secondary grid (see
 * `resolveLayoutSelection`), so being on that list is the whole test.
 *
 * The board is kept out of [switchLayoutIds]: the 🌐 key, the spacebar swipe
 * and the language picker change the language, and the tool, a spacebar
 * action ([SpaceSwipeAction.SECONDARY_LAYOUT]) or a key changes the shape.
 */
fun KeyboardSettings.pinnedBoardId(): String? =
    layoutBehavior.customLayoutToolId?.takeIf { it in enabledLayoutIds }

/**
 * The layouts "Layout in one-handed mode" can offer (#1230): the enabled
 * layouts that share their language with another enabled layout. Only those
 * can ever apply, since the pick is used only over a normal layout of its own
 * language ([oneHandedLayoutFor]); a layout alone in its language would be a
 * choice the keyboard ignores every time.
 */
fun KeyboardSettings.oneHandedLayoutChoices(): List<String> {
    val languageOf = enabledLayoutIds.associateWith { resolveLayoutLanguage(customLayouts, it).id }
    val perLanguage = languageOf.values.groupingBy { it }.eachCount()
    return enabledLayoutIds.filter { (perLanguage[languageOf[it]] ?: 0) > 1 }
}

/**
 * The layout one-handed mode draws over the normal layout [baseId], or null
 * when it draws [baseId] itself: "Same as normal", a pick that is no longer
 * enabled, the pick already being the normal layout, or a pick of another
 * language. That last rule is what keeps an English one-handed board from
 * following the user into French (#1230).
 *
 * Whether one-handed mode is in force at all is the caller's question: it
 * depends on the screen shape and the active Keyboard Mode, which the
 * settings alone do not know.
 */
fun KeyboardSettings.oneHandedLayoutFor(baseId: String): String? {
    val pick = oneHanded.layoutId ?: return null
    if (pick == baseId || pick !in enabledLayoutIds) return null
    if (resolveLayout(customLayouts, pick).id != pick) return null
    if (resolveLayoutLanguage(customLayouts, pick).id != resolveLayoutLanguage(customLayouts, baseId).id) return null
    return pick
}

package com.wasimaster.wmkeyboard.core.settings

/**
 * The settings as they apply on a given screen size: the stored values with a
 * tablet's defaults filled in for anything the user has never chosen.
 *
 * ## Defaults, not overrides
 *
 * A phone's key height and a three-tool toolbar are wrong on a twelve-inch
 * screen, but they are wrong as *defaults* — a user who has set a number
 * explicitly means it on every device they own. So each value here is gated on
 * evidence that the user never touched it, which is the presence of its DataStore
 * key rather than its value (see [LayoutBehaviorSettings.numberRowUntouched]).
 * Without that gate a tablet user could never turn the digit row off: the
 * overlay would put it straight back and the toggle would look broken.
 *
 * ## Where it sits in the overlay chain
 *
 * First, before everything: `applyDeviceForm` → `applyMode` →
 * [applyThemeOverrides] → [resolvedFor]. Each step is more specific than the
 * last, and this is the least specific of the four — it is what the device would
 * have shipped with. It has to run before `restrictedToDirectBoot` too, or a
 * tablet on the lock screen would get credential-backed tools re-pinned into a
 * session that cannot read them. `ThemeOverridesTest` pins the ordering.
 *
 * ## Instance stability
 *
 * Returns the receiver unchanged whenever it has nothing to say, phones included.
 * The service caches the result in its UI state and the composable remembers on
 * it by instance, so a fresh-but-equal copy per settings emission would cost a
 * full theme and screen-variant re-resolve on every unrelated preference write.
 * Same reason [applyThemeOverrides] and [resolvedFor] do it.
 */
fun KeyboardSettings.applyDeviceForm(form: DeviceForm): KeyboardSettings {
    if (!form.isTablet) return this

    val sizing = if (layoutBehavior.keyHeightUntouched) tabletSizing(form) else null
    val digits = layoutBehavior.numberRowUntouched && !numberRow
    // Exactly the shipped three is the free test for "never rearranged". A user
    // who dragged their bar back to precisely that gets their screen's default,
    // which is also what keeps "Reset pinned tools" from looking like a no-op.
    val pins = if (toolbarTools == DefaultToolbarKeys) tabletToolbarTools(form)?.toolKeys() else null

    if (sizing == null && !digits && pins == null) return this
    return copy(
        keyHeightDp = sizing?.keyHeightDp ?: keyHeightDp,
        numberRowHeightDp = sizing?.numberRowHeightDp ?: numberRowHeightDp,
        numberRow = if (digits) true else numberRow,
        toolbarTools = pins ?: toolbarTools,
    )
}

/**
 * Key heights a tablet starts at.
 *
 * Lower than a phone's 48/42 rather than higher, which is the opposite of the
 * intuition. A tablet is showing a sixth row (the digits are on by default here)
 * and has no more *proportion* of screen to spare than a phone does — what it has
 * is width. So the keys get shorter and the board takes a smaller share of a much
 * larger screen: roughly a third of a phone's height becomes roughly a quarter of
 * a large tablet's.
 *
 * Applied to the base values rather than seeded into
 * [KeyboardSettings.sizingOverrides]. That map means "what the user set for this
 * screen shape", and its editor reads an entry's presence as exactly that — a
 * default injected there would show as user-configured, and "Reset to portrait"
 * would delete the stored keys only for the default to reappear, which reads as a
 * button that does nothing. It also sidesteps [ScreenVariant] having one unfolded
 * tier where [DeviceForm] has two, with no persisted key suffix moved.
 */
private class TabletSizing(val keyHeightDp: Int, val numberRowHeightDp: Int)

// Deliberately not a SizingOverride: that type means "an entry in
// sizingOverrides", and the whole point of the note above is that these two
// numbers must not end up there.
private fun tabletSizing(form: DeviceForm): TabletSizing? = when (form) {
    DeviceForm.LARGE_TABLET -> TabletSizing(keyHeightDp = 44, numberRowHeightDp = 38)
    DeviceForm.SMALL_TABLET -> TabletSizing(keyHeightDp = 46, numberRowHeightDp = 40)
    DeviceForm.PHONE -> null
}

private fun tabletToolbarTools(form: DeviceForm): List<ToolbarTool>? = when (form) {
    DeviceForm.LARGE_TABLET -> LargeTabletToolbarTools
    DeviceForm.SMALL_TABLET -> SmallTabletToolbarTools
    DeviceForm.PHONE -> null
}

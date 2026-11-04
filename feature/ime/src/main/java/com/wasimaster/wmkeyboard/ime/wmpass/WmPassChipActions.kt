package com.wasimaster.wmkeyboard.ime.wmpass

/**
 * Where a WM Pass chip on the strip lands: the running keyboard service, set
 * when a field of WM Pass's own first offers one. Like `TodoChipActions`, so a
 * chip few people see adds no parameter to the strip's long chain of callers.
 */
object WmPassChipActions {
    @Volatile
    var handler: (String) -> Unit = {}
}

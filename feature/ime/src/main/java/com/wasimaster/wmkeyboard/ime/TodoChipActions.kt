package com.wasimaster.wmkeyboard.ime

/** Where the due task chip's buttons land: the running keyboard service, set when it starts. */
object TodoChipActions {
    @Volatile
    var handler: (Long, TodoChipAction) -> Unit = { _, _ -> }
}

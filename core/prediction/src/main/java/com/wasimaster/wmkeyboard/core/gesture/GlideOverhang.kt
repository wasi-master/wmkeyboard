package com.wasimaster.wmkeyboard.core.gesture

/**
 * A stroke's samples above the letters, folded back onto the top row (#970).
 *
 * The number row sits right over the top letter row, and a finger reaching for
 * `i` overshoots into the `8` above it as readily as it dips into the spacebar
 * below `n`. No key above the top row spells anything, so every such sample can
 * only have meant a top-row letter; read where it lies, it costs the decoder a
 * whole row of distance, charged as a miss. Measured with `wm-engine`'s
 * decoder on the bundled English list, keys 60 px wide on an 84 px row pitch,
 * twenty common words (`the`, `with`, `people`…) each started on the digit
 * above its first letter: a row up, all twenty still came first but at half
 * their margin (`the` 2.6 against 8.7 drawn on the keys); 1.2 rows up, past
 * the decoder's start radius, not one of them decoded at all. Folded, all
 * twenty came first at both heights (`the` 5.9). A top-row letter in the
 * middle of a word drawn over its digit loses less, about two nats, and the
 * fold gives most of it back.
 *
 * The fold is a clamp: a sample above [GlideKeyMap.topEdge] moves straight down
 * to it, keeping its x and its time. Measured against squashing the band by a
 * factor instead (a quarter, a half), the clamp scored highest on every word
 * tried. It moves no sample away from any key, since every key sits at or
 * below the edge, and a loop drawn for a doubled top-row letter keeps its
 * shape: it circles the key's centre, well inside the edge.
 *
 * Applied once, ahead of every engine (`SuggestionEngine.glide`), rather than
 * inside one decoder: the external engines read the stroke as it is handed to
 * them. Number-row keys that carry a letter join the grid
 * (`LayoutSet.glideKeys`), which raises [GlideKeyMap.topEdge] to them, so a
 * stroke through those keys is never folded away from them.
 */
object GlideOverhang {

    /**
     * [path] with every sample above [keys]' top edge moved down onto it, or
     * [path] itself when none is above it (the usual stroke, which costs one
     * pass and no allocation). Coordinates in the pixel space of keys
     * [keyWidth] wide, as [GlideKeyMap.of] took them.
     */
    fun fold(path: List<GesturePoint>, keys: GlideKeyMap, keyWidth: Float): List<GesturePoint> {
        val edge = keys.topEdge
        if (edge.isNaN() || keyWidth <= 0f) return path
        val edgePx = edge * keyWidth
        if (path.none { it.y < edgePx }) return path
        return path.map { if (it.y < edgePx) it.copy(y = edgePx) else it }
    }

    /**
     * Whether [path] lifts well clear of the letters, past the number row's
     * height above the top row: the way out of a glide begun on the wrong
     * word (#1310). The finger is taken up off the keyboard and lifted there,
     * and nothing is typed. [fold] reads an overshoot onto the number row as
     * the top row, which is a slip of a few millimetres; a lift a whole row
     * pitch above the top row's edge is past anything that fold was measured
     * on, so it is the escape it was before the fold. Only the lift counts:
     * a stroke that rises above the keys and comes back down is still a word.
     */
    fun escaped(path: List<GesturePoint>, keys: GlideKeyMap, keyWidth: Float): Boolean {
        val last = path.lastOrNull() ?: return false
        val edge = keys.topEdge
        val pitch = keys.rowPitch
        if (edge.isNaN() || pitch.isNaN() || keyWidth <= 0f) return false
        return last.y < (edge - pitch * ESCAPE_ROWS) * keyWidth
    }

    /** How far above the top row's edge, in row pitches, a lift is an escape: a number row's height. */
    const val ESCAPE_ROWS = 1f
}

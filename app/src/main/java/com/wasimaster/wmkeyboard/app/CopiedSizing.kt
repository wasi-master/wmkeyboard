package com.wasimaster.wmkeyboard.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The sizes the theme copier measured in a picture, in dp on this phone's screen.
 * A picture's proportions come out of the key grid, so these say how the keyboard
 * would look here at the same proportions, whatever screen the picture was from.
 */
data class CopiedSizing(
    val numberRow: Boolean,
    val landscape: Boolean,
    val fullScreenshot: Boolean,
    val keyHeightDp: Float,
    val numberRowHeightDp: Float,
    val bottomRowHeightDp: Float,
    val keyGapScale: Float,
    val sidePadLeftScale: Float,
    val sidePadRightScale: Float,
    val toolbarHeightDp: Float,
    val bottomGapDp: Float,
    val confidence: Float,
) {
    companion object {
        /** Reads the `_copy` block of the copier's JSON; null when there is none. */
        fun parse(json: String): CopiedSizing? = runCatching {
            val copy = Json.parseToJsonElement(json).jsonObject["_copy"]?.jsonObject ?: return null
            val sizing = copy["sizing"]?.jsonObject ?: return null
            val report = copy["report"]?.jsonObject
            fun JsonObject.number(key: String) = this[key]?.jsonPrimitive?.floatOrNull ?: 0f
            fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull ?: false
            CopiedSizing(
                numberRow = sizing.flag("numberRow"),
                landscape = sizing.flag("landscape"),
                fullScreenshot = sizing.flag("fullScreenshot"),
                keyHeightDp = sizing.number("keyHeightDp"),
                numberRowHeightDp = sizing.number("numberRowHeightDp"),
                bottomRowHeightDp = sizing.number("bottomRowHeightDp"),
                keyGapScale = sizing.number("keyGapScale"),
                sidePadLeftScale = sizing.number("sidePadLeftScale"),
                sidePadRightScale = sizing.number("sidePadRightScale"),
                toolbarHeightDp = sizing.number("toolbarHeightDp"),
                bottomGapDp = sizing.number("bottomGapDp"),
                confidence = report?.number("confidence") ?: 0f,
            )
        }.getOrNull()
    }
}

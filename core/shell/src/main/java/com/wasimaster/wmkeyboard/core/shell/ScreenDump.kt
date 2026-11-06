package com.wasimaster.wmkeyboard.core.shell

import android.content.Context

/** One piece of visible text in the app in front, with where it sits on screen. */
data class ScreenLine(
    val text: String,
    val className: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * The text of the app in front, through `uiautomator dump`, for the AI reply
 * panel: the chat being answered, read without an accessibility service and
 * with each bubble's position, which is what tells whose message it is.
 *
 * Runs only when asked ([read]), once, and keeps nothing: the dump file is
 * deleted in the same command that writes it.
 */
object ScreenDump {

    private const val DUMP_FILE = "/data/local/tmp/wmkb-screen.xml"

    /**
     * Only the attributes [parse] reads leave the shell, so a busy screen
     * stays under the service's 64 KiB output cap: a full dump does not.
     */
    private const val COMMAND =
        "uiautomator dump $DUMP_FILE >/dev/null 2>&1; " +
            "grep -oE 'text=\"[^\"]+\"[^>]*bounds=\"\\[[0-9]+,[0-9]+\\]\\[[0-9]+,[0-9]+\\]\"' $DUMP_FILE; " +
            "rm -f $DUMP_FILE"

    /** The screen's text lines, top to bottom; null when no shell door is open or the dump failed. */
    suspend fun read(context: Context): List<ScreenLine>? {
        val result = ShellAccess.exec(context, COMMAND) ?: return null
        return parse(result.output).takeIf { it.isNotEmpty() }
    }

    private val TEXT = Regex("text=\"([^\"]*)\"")
    private val CLASS = Regex("class=\"([^\"]*)\"")
    private val BOUNDS = Regex("bounds=\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\"")

    /** One line per node carrying text, in dump order (which is reading order). */
    fun parse(raw: String): List<ScreenLine> = raw.lineSequence().mapNotNull { line ->
        val text = TEXT.find(line)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
        val bounds = BOUNDS.find(line)?.groupValues ?: return@mapNotNull null
        if (text.isEmpty()) return@mapNotNull null
        ScreenLine(
            text = text,
            className = CLASS.find(line)?.groupValues?.get(1).orEmpty(),
            left = bounds[1].toInt(),
            top = bounds[2].toInt(),
            right = bounds[3].toInt(),
            bottom = bounds[4].toInt(),
        )
    }.toList()

    private fun unescape(s: String): String = s
        .replace("&#10;", "\n")
        .replace("&#13;", "")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    /** Times, dates and bare counters a chat screen is full of: never a message. */
    private val CHROME = Regex(
        "^(?:\\d{1,2}[:.]\\d{2}(?:\\s?[AaPp]\\.?[Mm]\\.?)?|\\d+|today|yesterday|\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?)$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The lines that look like chat messages, with a guess at who sent each.
     *
     * A bubble against the right edge and clear of the left one is the
     * user's own; everything else is theirs. Text fields (the user's draft),
     * the top bar (the contact's name) and times are dropped. The newest
     * [max] are kept, oldest first. The panel lets each side be flipped, so
     * this only has to be a good first guess.
     */
    fun messages(lines: List<ScreenLine>, screenWidth: Int, screenHeight: Int, max: Int = 12): List<Pair<Boolean, String>> {
        val topBar = screenHeight / 10
        return lines.asSequence()
            .filter { !it.className.endsWith("EditText") }
            .filter { it.top >= topBar }
            .filter { it.text.length >= 2 && !CHROME.matches(it.text) }
            .map { line ->
                val fromMe = screenWidth > 0 && line.left > screenWidth / 5 && line.right > screenWidth * 4 / 5
                fromMe to line.text
            }
            .toList()
            .takeLast(max)
    }
}

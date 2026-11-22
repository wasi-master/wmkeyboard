package com.wasimaster.wmkeyboard.core.tools

/**
 * What a model that sees is asked when it looks at a picture for one that
 * cannot, and how its answer is read back: the picture describer behind the
 * chat's "Look at a picture" tool and the reply tool's described screenshots.
 */
object AiPictureText {

    /** The describer's instructions for any picture. */
    const val DESCRIBE_SYSTEM: String =
        "You describe pictures for a model that cannot see them, so it can answer questions about them. " +
            "First say plainly what the picture shows: the subject, the setting, any people and what they are doing, " +
            "objects, colours, and anything unusual. Then, under the line \"Text:\", copy out every piece of text in " +
            "the picture exactly as written, in reading order, one line per line of text; write \"Text: none\" if " +
            "there is none. No preamble, no opinions, no guesses beyond what is visible."

    /** The user turn for [DESCRIBE_SYSTEM], with what the user wants to know when the caller has it. */
    fun describeRequest(question: String): String {
        val asked = question.trim()
        return if (asked.isEmpty()) {
            "Describe this picture."
        } else {
            "Describe this picture. The one asking wants to know: $asked\nAnswer that as well, from what is visible."
        }
    }

    /** The describer's instructions for a screenshot of a chat, which [chatTurns] reads back. */
    const val CHAT_SYSTEM: String =
        "You read screenshots of chat conversations. Write out the messages in the screenshot, oldest first, one " +
            "message per line. Start each line with \"Me:\" for a message the phone's owner sent (usually on the " +
            "right) or \"Them:\" for one anyone else sent (usually on the left). Copy each message's words exactly. " +
            "For a picture, sticker or voice note in the chat, write what it is in square brackets. Leave out the " +
            "times, the header with the contact's name, buttons, and the keyboard. If the screenshot is not a chat, " +
            "write one line starting \"Them:\" that says what it shows. Nothing else."

    const val CHAT_REQUEST: String = "Write out this chat."

    private val ME = Regex("^\\s*[*_]*\\s*(me|i|you \\(me\\)|owner)\\s*[*_]*\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
    private val THEM = Regex("^\\s*[*_]*\\s*(them|they|other|contact)\\s*[*_]*\\s*[:：]\\s*", RegexOption.IGNORE_CASE)

    /**
     * The describer's transcript of a chat screenshot as turns. A line with
     * neither mark continues the message above it (a message that ran over
     * a line break), or is the other person's when nothing is above it.
     * Blank lines, and any reasoning a thinking model left, are dropped.
     */
    fun chatTurns(answer: String): List<ReplyTurn> {
        val out = ArrayList<ReplyTurn>()
        for (raw in AiThinking.parts(answer).output.lineSequence()) {
            val line = raw.trim().trimStart('-', '•').trim()
            if (line.isEmpty()) continue
            val me = ME.find(line)
            val them = if (me == null) THEM.find(line) else null
            when {
                me != null -> line.substring(me.range.last + 1).trim().takeIf { it.isNotEmpty() }?.let { out += ReplyTurn(true, it) }
                them != null -> line.substring(them.range.last + 1).trim().takeIf { it.isNotEmpty() }?.let { out += ReplyTurn(false, it) }
                out.isNotEmpty() -> out[out.lastIndex] = out.last().let { it.copy(text = it.text + "\n" + line) }
                else -> out += ReplyTurn(false, line)
            }
        }
        return out
    }

    /** A description as the model in use reads it, out of a tool or a prompt. */
    fun described(number: Int, by: String, description: String): String =
        "Picture $number, as $by sees it:\n" + AiThinking.parts(description).output.trim()
}

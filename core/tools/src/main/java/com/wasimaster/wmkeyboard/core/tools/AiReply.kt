package com.wasimaster.wmkeyboard.core.tools

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.tools.R

/**
 * The AI reply tool (#234): drafting an answer to something somebody else
 * wrote.
 *
 * ## Why this is not just another AI action
 *
 * Every action in [BuiltInAiActions] transforms text the user has already
 * typed. A reply works on text the user has *not* typed and cannot select —
 * the other person's message lives in the app above the keyboard, which an
 * input method cannot read. So this file carries two things the actions do
 * not need: a way to say where the other person's words came from
 * ([ReplySource]), and a conversation rather than one blob ([ReplyTurn]).
 *
 * The second difference is a safety one. An action's input is the user's own
 * writing; a reply's input is a stranger's. Everything here is therefore
 * untrusted data, and [AiPrompts.replyPrompt] frames it as such.
 */

/** Where the other person's words came from. */
enum class ReplySource {
    /** The user selected them in a field that allows selecting. */
    SELECTION,

    /** The latest textual clip — the "paste their words" route. */
    CLIPBOARD,

    /** Typed or pasted into the panel's own box. */
    TYPED,

    /** Read off a screenshot, by the model's own eyes or by OCR. */
    SCREENSHOT,
}

/**
 * One message in the conversation being replied to.
 *
 * [fromMe] is what stops the model answering the user's own last line, and it
 * is the one thing neither the clipboard nor OCR can work out on its own —
 * which is why the panel lets it be flipped by hand.
 */
data class ReplyTurn(val fromMe: Boolean, val text: String)

/**
 * The conversation a reply is being written to, and where it was read from.
 *
 * [app] is the package of the field the keyboard is in, carried only so a
 * notification-fed context (a later phase) can be refused when it belongs to
 * a different app than the one being typed in.
 */
data class ReplyContext(
    val turns: List<ReplyTurn>,
    val source: ReplySource,
    val app: String? = null,
) {
    val isEmpty: Boolean get() = turns.none { it.text.isNotBlank() }

    /**
     * The transcript as the model sees it, trimmed to [maxChars].
     *
     * Trimmed from the **front**: the last message is the one being replied
     * to, so when something has to go it is the oldest. A turn is never cut in
     * half — a half sentence attributed to somebody is worse than one fewer
     * message.
     */
    fun transcript(maxChars: Int): String {
        val lines = turns.filter { it.text.isNotBlank() }
            .map { (if (it.fromMe) ME else THEM) + ": " + it.text.trim() }
        if (lines.isEmpty()) return ""
        val kept = ArrayDeque<String>()
        var used = 0
        for (line in lines.asReversed()) {
            val cost = line.length + 1
            // Always keep the last message, however long: a context trimmed to
            // nothing would send the model an empty conversation to answer.
            if (kept.isNotEmpty() && used + cost > maxChars) break
            kept.addFirst(line)
            used += cost
        }
        return kept.joinToString("\n").take(maxChars.coerceAtLeast(MIN_TRANSCRIPT))
    }

    companion object {
        const val THEM = "Them"
        const val ME = "Me"

        /**
         * A floor under the transcript budget. The take() above is a backstop
         * for a single message longer than the whole budget; cutting it to
         * nothing would leave the model with no conversation at all.
         */
        const val MIN_TRANSCRIPT = 200

        /**
         * Lines read off a screenshot, as a starting guess at who said what.
         *
         * OCR loses the thing that actually says it — which side of the screen
         * a bubble sits on — so everything starts as theirs and the user flips
         * what is not. Deliberately not guessed at from the wording: a wrong
         * guess is harder to spot than an honest default, because the labels
         * only show once the user looks at them.
         */
        fun fromOcrLines(lines: List<String>, source: ReplySource = ReplySource.SCREENSHOT): ReplyContext =
            ReplyContext(lines.filter { it.isNotBlank() }.map { ReplyTurn(fromMe = false, text = it) }, source)

        /** A single pasted or typed message, with no attribution to work out. */
        fun single(text: String, source: ReplySource): ReplyContext =
            ReplyContext(listOf(ReplyTurn(fromMe = false, text = text)), source)
    }
}

/** How long a drafted reply should be. */
enum class ReplyLength {
    SHORT, MEDIUM, LONG;

    /** The rule added to the prompt. */
    val rule: String
        get() = when (this) {
            SHORT -> "Keep each reply to one short sentence."
            MEDIUM -> "Keep each reply to one or two sentences."
            LONG -> "Each reply may run to a short paragraph."
        }
}

/** What a drafted reply may do with emoji. */
enum class ReplyEmoji {
    /** Use them as much as the other person does, and no more. */
    MATCH,
    ALLOW,
    AVOID;

    val rule: String
        get() = when (this) {
            MATCH -> "Use emoji only as freely as the other person does in the transcript."
            ALLOW -> "Emoji are welcome where they fit."
            AVOID -> "Use no emoji at all."
        }
}

/** How a screenshot reaches the model. */
enum class ReplyImageMode {
    /**
     * Send the picture to models that take pictures. For one that does not,
     * the picture describer writes it out when one is picked, and OCR reads
     * it otherwise.
     */
    AUTO,

    /** Always send the picture; fail rather than fall back to reading it here. */
    IMAGE,

    /** Always read it on the device and send the text. */
    OCR,

    /**
     * Always have the picture describer, a model that sees, write the chat
     * out as text, each message marked as the user's or the other person's,
     * and send that: the model in use need not see at all.
     */
    DESCRIBE,
}

/**
 * The knobs a drafted reply is written to, gathered so the prompt builder takes
 * one argument rather than six and so `:core:tools` keeps knowing nothing about
 * the settings module.
 *
 * [language] blank means the reply follows the conversation — which is the only
 * right default on a keyboard where somebody types Banglish to one person and
 * English to another, and where the keyboard's own locale says nothing about
 * either.
 */
data class ReplyStyle(
    val count: Int = 3,
    val length: ReplyLength = ReplyLength.MEDIUM,
    val emoji: ReplyEmoji = ReplyEmoji.MATCH,
    val persona: String = "",
    val language: String = "",
)

/**
 * The tones that ship with the reply tool.
 *
 * They are [AiActionSpec]s with [AiActionSurface.REPLY] rather than a parallel
 * type, so the action editor, the ordering, the hide list and the storage
 * codec are all the ones the AI tool already has — a tone the user writes is a
 * first-class tone, the same way a custom action is a first-class action.
 *
 * The shipped set is deliberately plain. The app the issue points at sells
 * flirting; what was actually asked for is help for somebody who does not know
 * what to say, so the list leads with the ordinary answers and keeps the
 * playful ones to the end.
 */
object BuiltInAiReplyTones {

    const val FRIENDLY_ID = "reply_friendly"
    const val SHORT_ID = "reply_short"
    const val WARM_ID = "reply_warm"
    const val KEEP_GOING_ID = "reply_keep_going"
    const val POLITE_ID = "reply_polite"
    const val AGREE_ID = "reply_agree"
    const val DECLINE_ID = "reply_decline"
    const val FUNNY_ID = "reply_funny"
    const val FLIRTY_ID = "reply_flirty"
    const val EXCITED_ID = "reply_excited"
    const val SORRY_ID = "reply_sorry"
    const val CUSTOM_ID = "reply_custom"

    /** Prefix of a tone the user writes, which is how the editor knows it is a tone and not an action. */
    const val CUSTOM_PREFIX = "reply_custom_"

    /**
     * Written for chat apps: the replies people actually send in a thread are
     * short, lower-key and unpunctuated next to an email, so every tone says
     * how a text reads rather than how a letter does. The one exception,
     * Polite, is for the colleague or stranger who gets the email register.
     */
    val tones: List<AiActionSpec> = listOf(
        AiActionSpec(
            id = FRIENDLY_ID,
            name = "Casual",
            surface = AiActionSurface.REPLY,
            task = "write a casual text the way friends message each other: relaxed, " +
                "natural, a sentence or two at most, light punctuation, no formal phrasing.",
        ),
        AiActionSpec(
            id = SHORT_ID,
            name = "Quick",
            surface = AiActionSurface.REPLY,
            task = "write a very short text: two to six words, the way people answer in a " +
                "chat when they are busy. A single word or emoji is fine when it fits.",
        ),
        AiActionSpec(
            id = KEEP_GOING_ID,
            name = "Keep it going",
            surface = AiActionSurface.REPLY,
            task = "answer what they said, then ask one easy, specific question back so " +
                "the chat has somewhere to go. Never ask more than one question.",
        ),
        AiActionSpec(
            id = AGREE_ID,
            name = "Sounds good",
            surface = AiActionSurface.REPLY,
            task = "say yes or agree, plainly and warmly, confirming any plan, time or " +
                "place they mentioned so nothing is left vague.",
        ),
        AiActionSpec(
            id = DECLINE_ID,
            name = "Say no",
            surface = AiActionSurface.REPLY,
            task = "decline or disagree kindly but clearly: the answer is no. One short " +
                "reason at most, no repeated apologies, and offer another time only if it " +
                "fits what they asked.",
        ),
        AiActionSpec(
            id = FUNNY_ID,
            name = "Funny",
            surface = AiActionSurface.REPLY,
            task = "write a playful, witty text that still answers what they said. Banter, " +
                "not a joke lecture, and never mean about them.",
        ),
        AiActionSpec(
            id = EXCITED_ID,
            name = "Excited",
            surface = AiActionSurface.REPLY,
            task = "react with genuine excitement or congratulations, the way someone " +
                "hyped for a friend texts back.",
        ),
        AiActionSpec(
            id = WARM_ID,
            name = "Supportive",
            surface = AiActionSurface.REPLY,
            task = "show you understood how they feel and that you are there for them, in " +
                "a short caring text, without unasked-for advice or sounding like a card.",
        ),
        AiActionSpec(
            id = SORRY_ID,
            name = "Apologise",
            surface = AiActionSurface.REPLY,
            task = "apologise sincerely and briefly for what the conversation shows went " +
                "wrong, without excuses or grovelling.",
        ),
        AiActionSpec(
            id = POLITE_ID,
            name = "Polite",
            surface = AiActionSurface.REPLY,
            task = "write a courteous, professional reply with no slang, suitable for a " +
                "colleague or someone you do not know well.",
        ),
        AiActionSpec(
            id = FLIRTY_ID,
            name = "Flirty",
            surface = AiActionSurface.REPLY,
            task = "write a warm, lightly flirty text. Keep it tasteful and never explicit.",
        ),
        AiActionSpec(
            id = CUSTOM_ID,
            name = "Custom",
            surface = AiActionSurface.REPLY,
            // Typed on the panel each run, in the box the messages are typed
            // in, which turns into the instruction box for the run.
            askEachRun = true,
            worksWithoutText = false,
        ),
    )

    fun byId(id: String): AiActionSpec? = tones.firstOrNull { it.id == id }

    fun isBuiltIn(id: String): Boolean = byId(id) != null

    /**
     * The translated name of a shipped tone, or null when the screen has to
     * fall back to the stored name — the same contract
     * [BuiltInAiActions.labelRes] has, and for the same reason: a tone the user
     * renamed keeps the name they typed.
     */
    @StringRes
    fun labelRes(spec: AiActionSpec): Int? = when {
        byId(spec.id)?.name != spec.name -> null
        spec.id == FRIENDLY_ID -> R.string.core_tools_reply_tone_friendly
        spec.id == SHORT_ID -> R.string.core_tools_reply_tone_short
        spec.id == WARM_ID -> R.string.core_tools_reply_tone_warm
        spec.id == KEEP_GOING_ID -> R.string.core_tools_reply_tone_keep_going
        spec.id == POLITE_ID -> R.string.core_tools_reply_tone_polite
        spec.id == AGREE_ID -> R.string.core_tools_reply_tone_agree
        spec.id == DECLINE_ID -> R.string.core_tools_reply_tone_decline
        spec.id == FUNNY_ID -> R.string.core_tools_reply_tone_funny
        spec.id == FLIRTY_ID -> R.string.core_tools_reply_tone_flirty
        spec.id == EXCITED_ID -> R.string.core_tools_reply_tone_excited
        spec.id == SORRY_ID -> R.string.core_tools_reply_tone_sorry
        spec.id == CUSTOM_ID -> R.string.core_tools_reply_tone_custom
        else -> null
    }

    val defaultOrder: List<String> = tones.map { it.id }
}

/**
 * Pulls the separate replies out of one response.
 *
 * Asking for several answers in one request is the only affordable way to do
 * it — three requests cost three times as much on a server and three times as
 * long on the device — but no model reliably writes them the way it was asked
 * to. They number them, they introduce them, they quote them, and a small
 * on-device model writes one paragraph and stops. So the contract in the
 * prompt is the happy path and this is what actually holds:
 *
 * **The last line of defence is [FALLBACK]**: anything that survives nothing
 * else comes back as a single candidate, because an empty panel after a real
 * answer arrived is the one outcome that is never acceptable.
 */
object AiReplyParse {

    /** Longest one drafted reply may be before it is cut. */
    const val MAX_CANDIDATE = 600

    /** Openings that are the model talking about the replies rather than one. */
    private val PREAMBLE = Regex(
        "^(sure|certainly|of course|here(\\s+are|\\s+is|'s)|okay|ok|option|reply|replies|" +
            "alternatives?|suggestions?)\\b.*:\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** Numbering and bullets a model adds in front of each reply. */
    private val BULLET = Regex("^\\s*(\\d+\\s*[.):\\-]|[-*•–—])\\s+")

    /** A label some models put in front: "Option 2:", "Reply:", "Friendly:". */
    private val LABEL = Regex("^\\s*(option|reply|version|alternative)\\s*\\d*\\s*:\\s*", RegexOption.IGNORE_CASE)

    private const val FALLBACK = 1

    /**
     * [raw] as up to [want] replies.
     *
     * Reasoning is stripped first ([AiThinking]) — a `<think>` block is full of
     * lines, and every one of them would read as a candidate.
     */
    fun candidates(raw: String, want: Int): List<String> {
        val body = AiThinking.stripped(raw).trim().ifBlank { raw.trim() }
        if (body.isBlank()) return emptyList()
        val found = lines(body)
        if (found.isEmpty()) {
            // Nothing survived the filters, but something did arrive: show it
            // rather than an empty panel.
            return listOf(body.take(MAX_CANDIDATE))
        }
        return found.take(want.coerceAtLeast(FALLBACK))
    }

    /**
     * The replies a response still being written has already finished, for
     * drawing them as they arrive.
     *
     * Only lines that have ended count: the line being written is left out,
     * so a card never shows half a sentence and never changes after it
     * appears — which was the reason the first version waited for the whole
     * answer. No [FALLBACK] either: an unbroken paragraph is not settled until
     * the response is done, and [candidates] owns that case. Empty while the
     * model is still inside a reasoning block.
     */
    fun settled(partial: String, want: Int): List<String> {
        val open = partial.lastIndexOf("<think>")
        if (open >= 0 && partial.indexOf("</think>", open) < 0) return emptyList()
        val end = partial.lastIndexOf('\n')
        if (end < 0) return emptyList()
        val body = AiThinking.stripped(partial.substring(0, end)).trim()
        if (body.isBlank()) return emptyList()
        return lines(body).take(want.coerceAtLeast(FALLBACK))
    }

    /** [body]'s separate replies, cleaned and de-duplicated, in order. */
    private fun lines(body: String): List<String> {
        val lines = body.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { PREAMBLE.matches(it) }
            .map { clean(it) }
            .filter { it.isNotBlank() }
        val unique = LinkedHashMap<String, String>()
        for (line in lines) {
            val key = line.lowercase().trimEnd('.', '!', '?', ' ')
            if (key.isEmpty()) continue
            unique.putIfAbsent(key, line.take(MAX_CANDIDATE))
        }
        return unique.values.toList()
    }

    /** One line with its numbering, label and surrounding quotes taken off. */
    private fun clean(line: String): String {
        var s = line
        s = BULLET.replace(s, "")
        s = LABEL.replace(s, "")
        s = s.trim()
        // A model told not to quote its answer frequently quotes its answer.
        if (s.length >= 2) {
            val first = s.first()
            val last = s.last()
            val paired = (first == '"' && last == '"') ||
                (first == '“' && last == '”') ||
                (first == '\'' && last == '\'')
            if (paired) s = s.substring(1, s.length - 1).trim()
        }
        return s
    }
}

/**
 * A gathered message that reads as noise rather than words (#234): no word in
 * it is one the keyboard knows. What OCR makes of a sticker, a blurred avatar
 * or a cut-off line, and what a stray copy pastes in.
 *
 * Only Latin-script words are judged, because only they are checked against
 * the English the user asked about; a message in another script, or one that
 * is all emoji and numbers, is never marked. One- and two-letter words do not
 * count either way — "ok" and "u" are chat, and "xq" is not evidence of
 * anything.
 */
object ReplyText {

    private val WORD = Regex("[A-Za-z][A-Za-z']+")

    fun looksUnclear(text: String, isKnown: (String) -> Boolean): Boolean {
        val words = WORD.findAll(text).map { it.value.trim('\'') }.filter { it.length >= 3 }.toList()
        if (words.isEmpty()) return false
        return words.none { isKnown(it) || isKnown(it.lowercase()) }
    }
}

package com.wasimaster.wmkeyboard.core.settings

/**
 * Two ways into the settings app's feedback form besides About (#1242):
 * shaking the phone, and taking a screenshot, each while the settings app
 * is open. Both off by default: a shake in a pocket or a screenshot taken
 * for some other reason must not throw a form at anyone who never asked.
 */
data class FeedbackShortcutSettings(
    val shake: Boolean = false,
    val screenshot: Boolean = false,
    /** How a report reaches the Telegram group; see [TelegramFeedbackRoute]. */
    val telegramRoute: TelegramFeedbackRoute = TelegramFeedbackRoute.SHARE,
)

/**
 * Telegram's share sheet cannot be pointed at one topic of a group, so
 * there are two ways to get a report there, and the user picks.
 */
enum class TelegramFeedbackRoute(val id: String) {
    /** Hand the text and the screenshot to Telegram; its picker lists the group's topics. */
    SHARE("share"),

    /** Open the topic for the report's kind, with the text copied to paste. */
    TOPIC("topic"),
    ;

    companion object {
        fun of(id: String?): TelegramFeedbackRoute? = entries.firstOrNull { it.id == id }
    }
}

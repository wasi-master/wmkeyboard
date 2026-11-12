package com.wasimaster.wmkeyboard.ime.calendar

import com.wasimaster.wmkeyboard.core.settings.InviteService
import com.wasimaster.wmkeyboard.ime.calendar.invite.MobilizonClient

/**
 * The "Invite online" card (#1274): one calendar event on its way to the
 * user's Mobilizon instance or Pretix server, filled in from the event and
 * open to changes before it goes. Owned by [CalendarController] like the rest
 * of [CalendarUi].
 */
data class InviteUi(
    /** The services set up in settings, in the order the card offers them; empty sends the user there. */
    val services: List<InviteService>,
    val service: InviteService?,
    val draft: InviteDraft,
    /** Who a Mobilizon event can be posted as; null until the user asks for the list. */
    val organizers: List<MobilizonClient.Organizer>? = null,
    /** The pick from [organizers]; null is the account's own first profile. */
    val organizer: MobilizonClient.Organizer? = null,
    val loadingOrganizers: Boolean = false,
    val busy: Boolean = false,
    val error: InviteError? = null,
    /** The service's own words for [error], where it gave some. */
    val errorDetail: String? = null,
    /** What the last data-saver question was asked for, so "Go ahead" does that again. */
    val heldFor: InviteHeld? = null,
    val published: InvitePublished? = null,
)

/** What the invitation will say. [place] that is a link becomes the online address instead. */
data class InviteDraft(
    val title: String,
    val about: String,
    val place: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    /** The zone the event's wall-clock times are in; UTC for an all-day event, as in the calendar. */
    val zoneId: String,
    /** How many can sign up on Pretix; null for no limit. */
    val places: Int? = null,
    /** Shown in the service's public lists and search, or reachable only through its link. */
    val listed: Boolean = false,
)

/** A published invitation, and its link to send. */
data class InvitePublished(
    val service: InviteService,
    val url: String,
    /** False when Pretix made the event but would not open sign-ups: [adminUrl] says what is missing. */
    val live: Boolean = true,
    val adminUrl: String? = null,
    val problem: String? = null,
)

enum class InviteHeld { PUBLISH, ORGANIZERS }

enum class InviteError {
    /** Neither service is set up in settings. */
    NOT_SET_UP,

    /** No network. */
    OFFLINE,

    /** Data saving is on; the card asks before it reaches the network. */
    DATA_SAVER,

    /** Mobilizon no longer takes the account's sign-in and could not renew it. */
    SIGNED_OUT,

    /** The Pretix token was turned down. */
    BAD_LOGIN,

    /** The account may not create events there, or the organizer does not exist. */
    REFUSED,

    /** The service turned the event down; [InviteUi.errorDetail] says why. */
    REJECTED,

    /** No answer, or a broken one. */
    NETWORK,

    /** The address in settings is not a web address. */
    BAD_ADDRESS,

    /** The event has no title. */
    NO_TITLE,
}

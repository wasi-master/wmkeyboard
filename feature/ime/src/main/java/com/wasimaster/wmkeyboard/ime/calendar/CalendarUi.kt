package com.wasimaster.wmkeyboard.ime.calendar

import com.wasimaster.wmkeyboard.core.tools.DeviceCalendarEvent
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendarInfo
import com.wasimaster.wmkeyboard.core.tools.StoredEvent
import com.wasimaster.wmkeyboard.core.tools.calendar.CalendarOp
import com.wasimaster.wmkeyboard.core.tools.calendar.EditScope
import com.wasimaster.wmkeyboard.core.tools.calendar.EventDate
import com.wasimaster.wmkeyboard.core.tools.calendar.EventDraft
import com.wasimaster.wmkeyboard.core.tools.calendar.Guest
import com.wasimaster.wmkeyboard.core.tools.calendar.SeriesInfo

/**
 * Everything the Calendar panel draws, owned by [CalendarController] rather
 * than `KeyboardUiState`, as the Clock's is. Only the text being typed into
 * the composer ([CalendarTextEntry]) lives in the keyboard's state, because
 * the shared capture path works on that.
 */
data class CalendarUi(
    val today: EventDate,
    val shownYear: Int = today.year,
    val shownMonth: Int = today.month,
    val selected: EventDate = today,
    /** Days of the shown month with something on, for the dots under the grid. */
    val busyDays: Set<Int> = emptySet(),
    /** The selected day's events; null while they load. */
    val dayEvents: List<DeviceCalendarEvent>? = null,
    val canRead: Boolean = false,
    val canWrite: Boolean = false,
    val contactsAllowed: Boolean = false,
    val view: CalendarView = CalendarView.MONTH,
    /** The event the detail sheet shows, read whole. */
    val detail: StoredEvent? = null,
    /** Where [detail] (or the event being edited) sits in its series; null for a one-off. */
    val series: SeriesInfo? = null,
    val draft: EventDraft? = null,
    /** The draft as it was opened, to tell what the user changed. */
    val draftBase: EventDraft? = null,
    /** The composer's open picker, if any. */
    val sheet: ComposeSheet? = null,
    val scopePrompt: ScopePrompt? = null,
    val calendars: List<DeviceCalendarInfo> = emptyList(),
    /** Other events the draft's time overlaps. */
    val conflicts: List<DeviceCalendarEvent> = emptyList(),
    val guestSuggestions: List<Guest> = emptyList(),
    val busy: Boolean = false,
    val error: CalendarError? = null,
    val undo: CalendarUndo? = null,
    val hour24: Boolean = false,
    /** A draft left when the panel closed, which the month view offers to pick up again. */
    val resumable: Boolean = false,
    /**
     * The weather beside each event card, by [weatherKey] (#1212). Kept as
     * days change, so the agenda's opened days keep theirs; empty while
     * "Weather on events" is off.
     */
    val weather: Map<String, EventWeatherChip> = emptyMap(),
    /** The open event on its way to Mobilizon or Pretix (#1274); set while [view] is INVITE. */
    val invite: InviteUi? = null,
) {
    val editing: Boolean get() = draft?.eventId != null
}

/** One occurrence of an event: the id repeats across a series, the start does not. */
fun weatherKey(event: DeviceCalendarEvent): String = "${event.id}:${event.begin}"

enum class CalendarView { MONTH, DETAIL, COMPOSE, INVITE }

/** The composer's pickers, one open at a time. */
enum class ComposeSheet { START_DATE, END_DATE, START_TIME, END_TIME, REPEAT, CALENDAR, REMINDERS, MORE }

/** Which occurrences of a repeating event a Save or a Delete is for, asked before it runs. */
data class ScopePrompt(val delete: Boolean, val scopes: List<EditScope>)

enum class CalendarError {
    /** Save with the end before the start. */
    END_BEFORE_START,

    /** No calendar the user may write in. */
    NO_CALENDAR,

    /** The write grant was refused. */
    NO_ACCESS,

    /** The provider said no. */
    WRITE_FAILED,

    /** No calendar app to hand the event to. */
    NO_APP,
}

/** What the composer's text boxes are for. */
enum class CalendarTextField { TITLE, LOCATION, NOTES, GUEST }

/**
 * The composer box the keys type into; lives in `KeyboardUiState` for the
 * shared capture path. [invite] when it is one of the invitation card's
 * boxes (#1274) rather than the composer's: title, place, or description.
 */
data class CalendarTextEntry(val field: CalendarTextField, val text: String = "", val invite: Boolean = false)

/** Something just done, and the writes that take it back. */
data class CalendarUndo(val kind: Kind, val ops: List<CalendarOp>) {
    enum class Kind { ADDED, CHANGED, DELETED }
}

/** Everything the panel can ask for, as one type, so the callback bundle stays one field. */
sealed interface CalendarAction {
    // Month
    data class Month(val by: Int) : CalendarAction
    data object Today : CalendarAction
    data class SelectDay(val date: EventDate) : CalendarAction
    /** A new event on [date], at [minute] of the day when the day view's hour was held (#1210). */
    data class NewEvent(val date: EventDate? = null, val minute: Int? = null) : CalendarAction

    /** The day view's drag (#1210): [event] moved by [minutes], its length kept. */
    data class MoveEvent(val event: DeviceCalendarEvent, val minutes: Int) : CalendarAction
    data object ResumeDraft : CalendarAction
    data object DiscardDraft : CalendarAction
    data class OpenEvent(val event: DeviceCalendarEvent) : CalendarAction

    /** The weather on an event's card: the Weather panel opens on that hour (#1212). */
    data class OpenWeather(val event: DeviceCalendarEvent) : CalendarAction

    // The Weather panel's hourly view for an event
    data object ForecastRefresh : CalendarAction
    data object ForecastBack : CalendarAction
    data object AskRead : CalendarAction
    data object OpenSettings : CalendarAction

    // Detail
    data object CloseDetail : CalendarAction
    data object EditEvent : CalendarAction
    data object DeleteEvent : CalendarAction
    data object InsertEvent : CalendarAction
    data object OpenInApp : CalendarAction

    // Invite online (#1274)
    data object InviteOpen : CalendarAction
    data object InviteBack : CalendarAction
    data class InvitePick(val service: com.wasimaster.wmkeyboard.core.settings.InviteService) : CalendarAction
    data class InviteType(val field: CalendarTextField) : CalendarAction
    data class InvitePlaces(val places: Int?) : CalendarAction
    data class InviteListed(val listed: Boolean) : CalendarAction
    data object InviteLoadOrganizers : CalendarAction
    data class InviteOrganizer(val organizer: com.wasimaster.wmkeyboard.ime.calendar.invite.MobilizonClient.Organizer?) : CalendarAction
    data object InvitePublish : CalendarAction

    /** Data saving is on and the user said go ahead anyway, for this invitation. */
    data object InviteAllowData : CalendarAction
    data object InviteInsertLink : CalendarAction
    data object InviteInsertMessage : CalendarAction
    data object InviteOpenLink : CalendarAction
    data object InviteOpenAdmin : CalendarAction
    data object InviteErrorDismiss : CalendarAction

    // Composer
    data class Draft(val draft: EventDraft) : CalendarAction
    data class Sheet(val sheet: ComposeSheet?) : CalendarAction
    data class Type(val field: CalendarTextField) : CalendarAction
    data class AddGuest(val guest: Guest) : CalendarAction
    data class RemoveGuest(val guest: Guest) : CalendarAction
    data object AskContacts : CalendarAction
    data object Save : CalendarAction
    data object CancelCompose : CalendarAction
    data object InsertDraft : CalendarAction
    data object DraftInApp : CalendarAction

    // Scope
    data class Scope(val scope: EditScope) : CalendarAction
    data object CancelScope : CalendarAction

    /** The Done beside the composer's text box: what Enter would do. */
    data object TextDone : CalendarAction

    data object Undo : CalendarAction
    data object UndoDismiss : CalendarAction
    data object ErrorDismiss : CalendarAction
}

package com.wasimaster.wmkeyboard.ime.calendar.invite

import com.wasimaster.wmkeyboard.core.settings.InviteService
import com.wasimaster.wmkeyboard.core.settings.InviteSettings
import com.wasimaster.wmkeyboard.core.tools.StoredEvent
import com.wasimaster.wmkeyboard.core.tools.calendar.EventTimes
import com.wasimaster.wmkeyboard.core.tools.invite.InviteRules
import com.wasimaster.wmkeyboard.ime.calendar.CalendarTextEntry
import com.wasimaster.wmkeyboard.ime.calendar.CalendarTextField
import com.wasimaster.wmkeyboard.ime.calendar.CalendarUi
import com.wasimaster.wmkeyboard.ime.calendar.CalendarView
import com.wasimaster.wmkeyboard.ime.calendar.InviteDraft
import com.wasimaster.wmkeyboard.ime.calendar.InviteError
import com.wasimaster.wmkeyboard.ime.calendar.InviteHeld
import com.wasimaster.wmkeyboard.ime.calendar.InvitePublished
import com.wasimaster.wmkeyboard.ime.calendar.InviteUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Currency
import java.util.Locale
import java.util.TimeZone

/** What the network allows the invitation card right now, read from the service on each press. */
internal data class InviteNet(val online: Boolean = true, val dataSaving: Boolean = false)

/**
 * The "Invite online" card's logic (#1274): a calendar event turned into a
 * Mobilizon event or a Pretix event with free sign-ups, and the link back.
 *
 * Nothing here reaches the network until a press: Publish, or the list of
 * who a Mobilizon event can be posted as. Each press checks the network
 * first; while data saving is on it asks once per invitation before going.
 * Mobilizon's tokens are renewed here when the instance says they lapsed,
 * and the new pair is written back to settings through [saveSettings].
 */
internal class InviteFlow(
    private val state: MutableStateFlow<CalendarUi>,
    private val scope: CoroutineScope,
    private val settings: () -> InviteSettings,
    private val saveSettings: ((InviteSettings) -> InviteSettings) -> Unit,
    private val net: () -> InviteNet,
    /** "Dinner, Fri 20 Nov, 7:00 – 9:00 PM · Café Nero", as Insert as text writes an event. */
    private val describe: (InviteDraft) -> String,
    /** The ticket's name on Pretix, in the user's language. */
    private val ticketName: () -> String,
) {

    private var job: Job? = null

    /** Data saving was answered with "go ahead" for this invitation. */
    private var dataAllowed = false

    private fun update(change: (InviteUi) -> InviteUi) {
        state.update { s -> s.invite?.let { s.copy(invite = change(it)) } ?: s }
    }

    private val ui: InviteUi? get() = state.value.invite

    /** The open event's card, filled in from [event]. */
    fun open(event: StoredEvent) {
        job?.cancel()
        dataAllowed = false
        val cfg = settings()
        val ready = cfg.ready
        val begin = event.instanceBegin
        val end = event.instanceEnd.takeIf { it > begin }
            ?: event.dtEnd?.takeIf { it > begin }
            ?: (begin + (EventTimes.parseDuration(event.duration) ?: HOUR_MS))
        val draft = InviteDraft(
            title = event.title.trim(),
            about = event.notes.trim(),
            place = event.location.trim(),
            begin = begin,
            end = end,
            allDay = event.allDay,
            zoneId = event.zone.id,
        )
        val service = cfg.lastService.takeIf { it in ready } ?: ready.firstOrNull()
        state.update {
            it.copy(
                view = CalendarView.INVITE,
                invite = InviteUi(
                    services = ready,
                    service = service,
                    draft = draft,
                    error = if (ready.isEmpty()) InviteError.NOT_SET_UP else null,
                ),
            )
        }
    }

    fun close() {
        job?.cancel()
        state.update { it.copy(view = if (it.detail != null) CalendarView.DETAIL else CalendarView.MONTH, invite = null) }
    }

    fun pick(service: InviteService) = update { it.copy(service = service, error = null, errorDetail = null) }

    fun places(places: Int?) = update { it.copy(draft = it.draft.copy(places = places)) }

    fun listed(listed: Boolean) = update { it.copy(draft = it.draft.copy(listed = listed)) }

    fun organizer(o: MobilizonClient.Organizer?) = update { it.copy(organizer = o) }

    fun dismissError() = update { it.copy(error = null, errorDetail = null, heldFor = null) }

    /** What a box starts with when the keys go to it. */
    fun textOf(field: CalendarTextField): String {
        val d = ui?.draft ?: return ""
        return when (field) {
            CalendarTextField.TITLE -> d.title
            CalendarTextField.LOCATION -> d.place
            CalendarTextField.NOTES -> d.about
            CalendarTextField.GUEST -> ""
        }
    }

    fun onText(entry: CalendarTextEntry) {
        update { u ->
            val d = u.draft
            u.copy(
                draft = when (entry.field) {
                    CalendarTextField.TITLE -> d.copy(title = entry.text.take(TITLE_MAX))
                    CalendarTextField.LOCATION -> d.copy(place = entry.text.take(TITLE_MAX))
                    CalendarTextField.NOTES -> d.copy(about = entry.text.take(ABOUT_MAX))
                    CalendarTextField.GUEST -> d
                },
                error = if (u.error == InviteError.NO_TITLE) null else u.error,
            )
        }
    }

    fun allowData() {
        dataAllowed = true
        val held = ui?.heldFor
        dismissError()
        when (held) {
            InviteHeld.ORGANIZERS -> loadOrganizers()
            else -> publish()
        }
    }

    /**
     * The network may be used for [what] right now; otherwise the card says
     * why in place: offline, or data saving on (with a "go ahead").
     */
    private fun mayGo(what: InviteHeld): Boolean {
        val n = net()
        if (!n.online) {
            update { it.copy(error = InviteError.OFFLINE, errorDetail = null, heldFor = what) }
            return false
        }
        if (n.dataSaving && !dataAllowed) {
            update { it.copy(error = InviteError.DATA_SAVER, errorDetail = null, heldFor = what) }
            return false
        }
        return true
    }

    // ---- Mobilizon --------------------------------------------------------

    /** Mobilizon's tokens as they stand now, renewed as needed during one press. */
    private class Session(var access: String, var refresh: String)

    /**
     * [block] with the account's access token, renewing the pair once when
     * Mobilizon says it lapsed. A renewal that is turned down means the user
     * has to sign in again from settings.
     */
    private fun <T> withToken(client: MobilizonClient, session: Session, block: (String) -> T): T = try {
        block(session.access)
    } catch (e: InviteFailure) {
        if (e.kind != InviteFailure.Kind.SIGNED_OUT || session.refresh.isBlank()) throw e
        val fresh = client.refresh(session.refresh)
        session.access = fresh.access
        if (fresh.refresh.isNotBlank()) session.refresh = fresh.refresh
        val access = session.access
        val refresh = session.refresh
        saveSettings { it.copy(mobilizonAccessToken = access, mobilizonRefreshToken = refresh) }
        block(session.access)
    }

    fun loadOrganizers() {
        val u = ui ?: return
        if (u.loadingOrganizers || !mayGo(InviteHeld.ORGANIZERS)) return
        val cfg = settings()
        update { it.copy(loadingOrganizers = true, error = null, errorDetail = null) }
        job = scope.launch {
            val client = MobilizonClient(cfg.mobilizonUrl)
            val session = Session(cfg.mobilizonAccessToken, cfg.mobilizonRefreshToken)
            val got = withContext(Dispatchers.IO) { runCatching { withToken(client, session) { client.organizers(it) } } }
            got.fold(
                onSuccess = { list -> update { it.copy(loadingOrganizers = false, organizers = list) } },
                onFailure = { e -> fail(e) { it.copy(loadingOrganizers = false) } },
            )
        }
    }

    // ---- Publishing -------------------------------------------------------

    fun publish() {
        val u = ui ?: return
        val service = u.service ?: return
        if (u.busy) return
        if (u.draft.title.isBlank()) {
            update { it.copy(error = InviteError.NO_TITLE, errorDetail = null) }
            return
        }
        if (!mayGo(InviteHeld.PUBLISH)) return
        val cfg = settings()
        val draft = u.draft
        val organizer = u.organizer
        update { it.copy(busy = true, error = null, errorDetail = null, heldFor = null) }
        saveSettings { it.copy(lastService = service) }
        job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (service) {
                        InviteService.MOBILIZON -> publishMobilizon(cfg, draft, organizer)
                        InviteService.PRETIX -> publishPretix(cfg, draft)
                    }
                }
            }
            result.fold(
                onSuccess = { done -> update { it.copy(busy = false, published = done) } },
                onFailure = { e -> fail(e) { it.copy(busy = false) } },
            )
        }
    }

    private fun publishMobilizon(cfg: InviteSettings, d: InviteDraft, organizer: MobilizonClient.Organizer?): InvitePublished {
        val client = MobilizonClient(cfg.mobilizonUrl)
        val session = Session(cfg.mobilizonAccessToken, cfg.mobilizonRefreshToken)
        val span = InviteRules.span(d.begin, d.end, d.allDay, TimeZone.getTimeZone(d.zoneId), TimeZone.getDefault())
        val placeIsLink = InviteRules.isLink(d.place)
        val event = MobilizonClient.NewEvent(
            organizer = organizer,
            title = d.title.trim(),
            descriptionHtml = InviteRules.html(d.about),
            beginsOn = InviteRules.iso(span.begin, span.zone),
            endsOn = InviteRules.iso(span.end, span.zone),
            place = d.place.trim().takeIf { it.isNotEmpty() && !placeIsLink },
            onlineAddress = if (placeIsLink) d.place.trim() else InviteRules.firstLink(d.about),
            listed = d.listed,
            allDay = span.allDay,
        )
        val made = withToken(client, session) { client.create(it, event) }
        return InvitePublished(InviteService.MOBILIZON, made.url)
    }

    private fun publishPretix(cfg: InviteSettings, d: InviteDraft): InvitePublished {
        val client = PretixClient(cfg.pretixUrl, cfg.pretixToken, cfg.pretixOrganizer)
        val span = InviteRules.span(d.begin, d.end, d.allDay, TimeZone.getTimeZone(d.zoneId), TimeZone.getDefault())
        val fallbackCurrency = runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull() ?: "EUR"
        val suffix = InviteRules.suffix()
        val made = client.create(
            PretixClient.NewEvent(
                title = d.title.trim(),
                description = d.about.trim(),
                dateFrom = InviteRules.iso(span.begin, span.zone),
                dateTo = InviteRules.iso(span.end, span.zone),
                place = d.place.trim().takeIf { it.isNotEmpty() },
                timezone = span.zone.id,
                currency = InviteRules.currency(cfg.pretixCurrency, fallbackCurrency),
                listed = d.listed,
                places = d.places,
                ticketName = ticketName(),
                languages = listOf("en", Locale.getDefault().language).filter { it.isNotBlank() },
            ),
        ) { attempt -> InviteRules.slug(d.title, if (attempt == 0) suffix else InviteRules.suffix()) }
        return InvitePublished(InviteService.PRETIX, made.publicUrl, live = made.live, adminUrl = made.adminUrl, problem = made.problem)
    }

    /** The message to send: what [describe] writes, and the link under it. */
    fun message(): String? {
        val u = ui ?: return null
        val link = u.published?.url ?: return null
        return describe(u.draft) + "\n" + link
    }

    private fun fail(e: Throwable, also: (InviteUi) -> InviteUi) {
        val failure = e as? InviteFailure
        val error = when (failure?.kind) {
            InviteFailure.Kind.BAD_ADDRESS -> InviteError.BAD_ADDRESS
            InviteFailure.Kind.SIGNED_OUT -> InviteError.SIGNED_OUT
            InviteFailure.Kind.BAD_LOGIN -> if (ui?.service == InviteService.MOBILIZON) InviteError.SIGNED_OUT else InviteError.BAD_LOGIN
            InviteFailure.Kind.REFUSED -> InviteError.REFUSED
            InviteFailure.Kind.REJECTED -> InviteError.REJECTED
            InviteFailure.Kind.NETWORK, null -> InviteError.NETWORK
        }
        val detail = (failure?.message ?: e.message)?.takeIf { it.isNotBlank() }
        update { also(it).copy(error = error, errorDetail = detail) }
    }

    companion object {
        const val TITLE_MAX = 200
        const val ABOUT_MAX = 4000
        private const val HOUR_MS = 3_600_000L
    }
}

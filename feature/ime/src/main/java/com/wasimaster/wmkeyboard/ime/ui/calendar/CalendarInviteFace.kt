package com.wasimaster.wmkeyboard.ime.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Group
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LocationOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notes
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Person
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Send
import com.wasimaster.wmkeyboard.core.settings.InviteService
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.tools.invite.InviteRules
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.calendar.CalendarAction
import com.wasimaster.wmkeyboard.ime.calendar.CalendarTextField
import com.wasimaster.wmkeyboard.ime.calendar.CalendarUi
import com.wasimaster.wmkeyboard.ime.calendar.InviteDraft
import com.wasimaster.wmkeyboard.ime.calendar.InviteError
import com.wasimaster.wmkeyboard.ime.calendar.InviteHeld
import com.wasimaster.wmkeyboard.ime.calendar.InvitePublished
import com.wasimaster.wmkeyboard.ime.calendar.InviteUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.clock.Banner
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton
import com.wasimaster.wmkeyboard.ime.ui.clock.clockChip
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.kit.SegmentedPill
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import com.wasimaster.wmkeyboard.ime.ui.kit.pressScale
import java.util.TimeZone

/**
 * "Invite online" (#1274): the open event on its way to Mobilizon or Pretix.
 * Which service, then the invitation as a card (title and when; a tap sends
 * the keys to the title), then its pieces as chips the keys can fill in
 * (place, description) or that flip (link only or listed; who posts it on
 * Mobilizon; how many places on Pretix). Publish is the pill along the
 * bottom. Once it is out, the card turns into the link with Insert link and
 * Insert invitation, the two ways to send it.
 */
@Composable
internal fun InviteFace(invite: InviteUi, ui: CalendarUi, animate: Boolean, act: (CalendarAction) -> Unit) {
    val published = invite.published
    when {
        invite.services.isEmpty() -> EmptyState(
            Icons.Outlined.Public,
            title = stringResource(R.string.ime_invite_not_set_up_title),
            body = stringResource(R.string.ime_invite_not_set_up_body),
        ) {
            PillButton(stringResource(R.string.ime_invite_open_settings), primary = true, height = 32.dp) { act(CalendarAction.OpenSettings) }
        }
        published != null -> PublishedFace(invite, published, animate, act)
        else -> EditFace(invite, ui, animate, act)
    }
}

/** Back to the event, and what the card is. */
@Composable
internal fun RowScope.InviteHeader(act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    RoundButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ime_calendar_back_desc), size = 30.dp) {
        act(CalendarAction.InviteBack)
    }
    Spacer(Modifier.width(8.dp))
    Text(
        stringResource(R.string.ime_invite_title),
        color = kb.modifierKeyText,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.weight(1f),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditFace(invite: InviteUi, ui: CalendarUi, animate: Boolean, act: (CalendarAction) -> Unit) {
    val d = invite.draft
    val service = invite.service
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (invite.services.size > 1) {
                SegmentedPill(
                    labels = invite.services.map { serviceName(it) },
                    selected = invite.services.indexOf(service).coerceAtLeast(0),
                    onSelect = { act(CalendarAction.InvitePick(invite.services[it])) },
                    segmentWidth = 92.dp,
                    height = 28.dp,
                )
            } else if (service != null) {
                Text(
                    stringResource(R.string.ime_invite_publishing_to, serviceName(service)),
                    color = LocalKbTheme.current.secondaryText,
                    fontSize = 11.sp,
                )
            }
            invite.error?.let { e -> ErrorBanner(e, invite, act) }
            InvitePreview(d, ui.hour24, animate) { act(CalendarAction.InviteType(CalendarTextField.TITLE)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var i = 1
                val placeIsLink = InviteRules.isLink(d.place)
                InviteChip(
                    if (placeIsLink) Icons.Outlined.Link else Icons.Outlined.LocationOn,
                    d.place.ifBlank { stringResource(R.string.ime_invite_place_hint) },
                    filled = d.place.isNotBlank(),
                    index = i++,
                    animate = animate,
                ) { act(CalendarAction.InviteType(CalendarTextField.LOCATION)) }
                InviteChip(
                    Icons.Outlined.Notes,
                    d.about.lineSequence().firstOrNull { it.isNotBlank() } ?: stringResource(R.string.ime_invite_about_hint),
                    filled = d.about.isNotBlank(),
                    index = i++,
                    animate = animate,
                ) { act(CalendarAction.InviteType(CalendarTextField.NOTES)) }
                InviteChip(
                    if (d.listed) Icons.Outlined.Public else Icons.Outlined.Lock,
                    stringResource(if (d.listed) R.string.ime_invite_listed else R.string.ime_invite_link_only),
                    filled = true,
                    index = i++,
                    animate = animate,
                ) { act(CalendarAction.InviteListed(!d.listed)) }
                if (service == InviteService.MOBILIZON) {
                    InviteChip(
                        if (invite.organizer?.groupId != null) Icons.Outlined.Group else Icons.Outlined.Person,
                        invite.organizer?.let { stringResource(R.string.ime_invite_post_as, it.name) }
                            ?: stringResource(if (invite.loadingOrganizers) R.string.ime_invite_post_as_loading else R.string.ime_invite_post_as_default),
                        filled = invite.organizer != null,
                        active = invite.organizers != null,
                        index = i++,
                        animate = animate,
                    ) { act(CalendarAction.InviteLoadOrganizers) }
                }
            }
            if (service == InviteService.MOBILIZON) {
                invite.organizers?.let { list -> OrganizerChoices(list, invite, act) }
            }
            if (service == InviteService.PRETIX) {
                PlacesChoices(d.places, act)
            }
            Text(
                stringResource(
                    when {
                        service == InviteService.PRETIX -> R.string.ime_invite_note_pretix
                        else -> R.string.ime_invite_note_mobilizon
                    },
                ),
                color = LocalKbTheme.current.secondaryText,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        ActionBar(
            listOf(
                KitAction(
                    stringResource(if (invite.busy) R.string.ime_invite_publishing else R.string.ime_invite_publish),
                    Icons.Outlined.Send,
                    enabled = !invite.busy && service != null,
                    primary = true,
                ) { act(CalendarAction.InvitePublish) },
            ),
            Modifier.padding(vertical = 4.dp),
        )
    }
}

/** The invitation as a card: when, then the title (or its hint). A tap sends the keys to the title. */
@Composable
private fun InvitePreview(d: InviteDraft, hour24: Boolean, animate: Boolean, onTitle: (() -> Unit)?) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.cardShape()
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .popIn(0, animate)
            .pressScale(source, animate && onTitle != null)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip)
            .background(kb.accent.copy(alpha = TINT_ALPHA))
            .chipBorder(kb, shape)
            .then(
                if (onTitle != null) {
                    Modifier.clickable(interactionSource = source, indication = null) {
                        feedback()
                        onTitle()
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            rangeText(d.begin, d.end, d.allDay, TimeZone.getTimeZone(d.zoneId), hour24),
            color = kb.secondaryText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            d.title.ifBlank { stringResource(R.string.ime_calendar_title_hint) },
            color = if (d.title.isBlank()) kb.secondaryText else kb.suggestionText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What the folded panel shows while the keys type into one of the card's boxes. */
@Composable
internal fun InviteTypingPreview(invite: InviteUi, hour24: Boolean) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        InvitePreview(invite.draft, hour24, animate = false, onTitle = null)
    }
}

/** Who the Mobilizon event is posted as: the account's profiles, then the groups they post for. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrganizerChoices(list: List<com.wasimaster.wmkeyboard.ime.calendar.invite.MobilizonClient.Organizer>, invite: InviteUi, act: (CalendarAction) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEachIndexed { i, o ->
            val picked = invite.organizer?.let { it.actorId == o.actorId && it.groupId == o.groupId }
                ?: (o.groupId == null && list.indexOfFirst { it.groupId == null } == i)
            InviteChip(
                if (o.groupId != null) Icons.Outlined.Group else Icons.Outlined.Person,
                o.name.ifBlank { o.handle },
                filled = true,
                active = picked,
                index = i,
                animate = false,
            ) { act(CalendarAction.InviteOrganizer(o)) }
        }
    }
}

/** How many can sign up on Pretix. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlacesChoices(places: Int?, act: (CalendarAction) -> Unit) {
    Text(
        stringResource(R.string.ime_invite_places_label),
        color = LocalKbTheme.current.secondaryText,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PLACES.forEachIndexed { i, n ->
            InviteChip(
                Icons.Outlined.Group,
                if (n == null) stringResource(R.string.ime_invite_places_any) else n.toString(),
                filled = true,
                active = places == n,
                index = i,
                animate = false,
            ) { act(CalendarAction.InvitePlaces(n)) }
        }
    }
}

/** What went wrong, in place, with the one thing that fixes it. */
@Composable
private fun ErrorBanner(e: InviteError, invite: InviteUi, act: (CalendarAction) -> Unit) {
    val retry = if (invite.heldFor == InviteHeld.ORGANIZERS) CalendarAction.InviteLoadOrganizers else CalendarAction.InvitePublish
    val text = inviteErrorText(e, invite.service).let { base ->
        val detail = invite.errorDetail
        if (detail != null && (e == InviteError.REJECTED || e == InviteError.REFUSED || e == InviteError.NETWORK)) "$base $detail" else base
    }
    val (label, action) = when (e) {
        InviteError.DATA_SAVER -> stringResource(R.string.ime_invite_go_ahead) to CalendarAction.InviteAllowData
        InviteError.OFFLINE, InviteError.NETWORK, InviteError.REJECTED -> stringResource(R.string.ime_invite_try_again) to retry
        InviteError.NO_TITLE -> stringResource(R.string.ime_invite_add_title) to CalendarAction.InviteType(CalendarTextField.TITLE)
        InviteError.SIGNED_OUT, InviteError.BAD_LOGIN, InviteError.REFUSED, InviteError.BAD_ADDRESS, InviteError.NOT_SET_UP ->
            stringResource(R.string.ime_invite_open_settings) to CalendarAction.OpenSettings
    }
    Banner(text, label) { act(action) }
}

@Composable
private fun inviteErrorText(e: InviteError, service: InviteService?): String {
    val name = service?.let { serviceName(it) } ?: ""
    return when (e) {
        InviteError.NOT_SET_UP -> stringResource(R.string.ime_invite_not_set_up_body)
        InviteError.OFFLINE -> stringResource(R.string.ime_invite_error_offline)
        InviteError.DATA_SAVER -> stringResource(R.string.ime_invite_error_data_saver)
        InviteError.SIGNED_OUT -> stringResource(R.string.ime_invite_error_signed_out, name)
        InviteError.BAD_LOGIN -> stringResource(R.string.ime_invite_error_bad_token, name)
        InviteError.REFUSED -> stringResource(R.string.ime_invite_error_refused, name)
        InviteError.REJECTED -> stringResource(R.string.ime_invite_error_rejected, name)
        InviteError.NETWORK -> stringResource(R.string.ime_invite_error_network, name)
        InviteError.BAD_ADDRESS -> stringResource(R.string.ime_invite_error_address, name)
        InviteError.NO_TITLE -> stringResource(R.string.ime_invite_error_no_title)
    }
}

/**
 * Published: the link, and the ways to send it. A Pretix event whose shop
 * would not open says so, with the page that names what is missing.
 */
@Composable
private fun PublishedFace(invite: InviteUi, p: InvitePublished, animate: Boolean, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!p.live) {
                Banner(
                    stringResource(R.string.ime_invite_not_live, p.problem.orEmpty()).trim(),
                    stringResource(R.string.ime_invite_fix_in_pretix),
                ) { act(CalendarAction.InviteOpenAdmin) }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .popIn(0, animate, key = p.url)
                    .clip(shape)
                    .frostGlass(kb, shape, FrostSurface.CARDS)
                    .background(kb.chip)
                    .background(kb.accent.copy(alpha = TINT_ALPHA))
                    .chipBorder(kb, shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = kb.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(if (p.live) R.string.ime_invite_published else R.string.ime_invite_made, serviceName(p.service)),
                        color = kb.secondaryText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    invite.draft.title,
                    color = kb.suggestionText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Link, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(p.url, color = kb.accent, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        ActionBar(
            listOf(
                KitAction(stringResource(R.string.ime_invite_insert_link), Icons.Outlined.Link, primary = true) { act(CalendarAction.InviteInsertLink) },
                KitAction(stringResource(R.string.ime_invite_insert_message), Icons.Outlined.EditNote) { act(CalendarAction.InviteInsertMessage) },
                KitAction(stringResource(R.string.ime_invite_open_page), Icons.Outlined.OpenInNew) { act(CalendarAction.InviteOpenLink) },
            ),
            Modifier.padding(vertical = 4.dp),
        )
    }
}

/** One piece of the invitation, as the composer's chips are: dim while unset, selected while picked. */
@Composable
private fun InviteChip(
    icon: ImageVector,
    label: String,
    filled: Boolean,
    index: Int,
    animate: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val source = remember { MutableInteractionSource() }
    val ink = when {
        active -> kb.chipActiveText
        filled -> kb.chipText
        else -> kb.chipText.copy(alpha = kb.chipText.alpha * 0.6f)
    }
    Row(
        Modifier
            .popIn(index.coerceAtMost(MAX_STAGGER), animate)
            .pressScale(source, animate)
            .clockChip(selected = active)
            .clickable(interactionSource = source, indication = null) {
                feedback()
                onClick()
            }
            .padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (active) kb.chipActiveText else if (filled) kb.accent else ink, modifier = Modifier.size(14.dp))
        Text(
            label,
            color = ink,
            fontSize = 12.sp,
            fontWeight = if (filled) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
    }
}

@Composable
private fun serviceName(service: InviteService): String = stringResource(
    when (service) {
        InviteService.MOBILIZON -> R.string.ime_invite_mobilizon
        InviteService.PRETIX -> R.string.ime_invite_pretix
    },
)

/** The place counts Pretix's card offers; null is no limit. */
private val PLACES = listOf(null, 10, 20, 50, 100, 200)

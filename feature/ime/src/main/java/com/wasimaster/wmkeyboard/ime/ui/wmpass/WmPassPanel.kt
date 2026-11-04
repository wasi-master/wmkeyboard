package com.wasimaster.wmkeyboard.ime.ui.wmpass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Badge
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Key
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Payments
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Restore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Smartphone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StickyNote2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Storage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Terminal
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerifiedUser
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VpnKey
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Wifi
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.focusRing
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.wmpass.VaultWire
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassAction
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassBridge
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassCallbacks
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassNotice
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassPage
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassTextEntry
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassTextField
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassUi

/** The panel's height while its text box shares the window with the key rows. */
private val WmPassTypingHeight = 164.dp

/** What a secret shows in place of itself. */
private const val DOTS = "••••••••"

/**
 * WM Pass's vault as a password book in the keyboard (#1077). The list holds
 * no secret: an item opens to its fields, a secret is asked for on the tap
 * that types it and goes to the field the way a generated password does, and
 * a copy is made by WM Pass itself. A new login and a new password are typed
 * into the panel's own box and handed to WM Pass on Save. Unlocking always
 * happens in WM Pass. `WmPassController` owns all of it; this draws what it
 * publishes.
 */
@Composable
internal fun WmPassPanel(
    state: KeyboardUiState,
    callbacks: WmPassCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val typing = state.wmPassText
    val open = ui.status == WmPassBridge.Status.OPEN && !state.fieldIncognito

    PanelFocusTarget(
        panel = PanelMode.WM_PASS,
        region = FocusRegion.RESULTS,
        count = if (open && typing == null && ui.page == WmPassPage.LIST) ui.rows.size else 0,
        columns = 1,
    ) { index -> ui.rows.getOrNull(index)?.let { act(WmPassAction.Open(it.id)) } }

    FullBleedTool(
        state = state,
        title = if (open) "" else stringResource(R.string.ime_tool_wm_pass),
        onClose = onClose,
        compact = typing != null,
        compactHeight = WmPassTypingHeight,
        headerActions = {
            when {
                !open -> Unit
                typing != null -> TypingHeader(typing, ui, act)
                ui.page == WmPassPage.LIST -> ListHeader(ui, act)
                else -> PageHeader(ui, act)
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                state.fieldIncognito -> Message(
                    stringResource(R.string.ime_wmpass_private_field),
                    stringResource(R.string.ime_wmpass_open),
                    { act(WmPassAction.OpenApp) },
                )
                ui.status == null -> Unit
                ui.status != WmPassBridge.Status.OPEN -> StatusMessage(ui.status, act)
                typing != null -> TypingBody(typing, ui, act)
                ui.page == WmPassPage.LIST -> ItemList(state, ui, act)
                ui.page == WmPassPage.ITEM -> ItemFields(ui, act)
                ui.page == WmPassPage.ADD -> AddForm(ui, act)
                ui.page == WmPassPage.CHANGE -> ChangeForm(ui, act)
            }
            ui.notice?.let { NoticeLine(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

// ---- headers ----

/** The search pill, which hands the keys to the panel's box, and Add a login. */
@Composable
private fun RowScope.ListHeader(ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    BoxPill(Modifier.weight(1f), onClick = { act(WmPassAction.StartSearch) }) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        SearchQueryText(
            query = ui.query,
            placeholder = stringResource(R.string.ime_wmpass_search_hint),
            active = false,
            textColor = kb.chipText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (ui.query.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_wmpass_clear_search_desc),
                tint = kb.secondaryText,
                modifier = Modifier.size(16.dp).clickable { act(WmPassAction.ClearSearch) },
            )
        }
    }
    Spacer(Modifier.width(6.dp))
    ToolPanelChip(stringResource(R.string.ime_wmpass_add), icon = Icons.Outlined.Add) { act(WmPassAction.StartAdd) }
    Spacer(Modifier.width(4.dp))
}

/** Back to the list (or the item), what is open, and the one thing to do with it. */
@Composable
private fun RowScope.PageHeader(ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    ToolPanelChip(stringResource(R.string.ime_wmpass_back), icon = Icons.AutoMirrored.Outlined.ArrowBack) { act(WmPassAction.Back) }
    Spacer(Modifier.width(8.dp))
    Text(
        when (ui.page) {
            WmPassPage.ADD -> stringResource(R.string.ime_wmpass_new_login)
            WmPassPage.CHANGE -> stringResource(R.string.ime_wmpass_new_password_for, ui.item?.title.orEmpty())
            else -> ui.item?.title.orEmpty()
        },
        color = kb.suggestionText,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    when (ui.page) {
        WmPassPage.ITEM -> ToolPanelChip(stringResource(R.string.ime_wmpass_open_item), icon = Icons.Outlined.OpenInNew) { act(WmPassAction.OpenInPass) }
        else -> ToolPanelChip(stringResource(R.string.ime_wmpass_save), selected = true, enabled = canSave(ui)) { act(WmPassAction.Save) }
    }
    Spacer(Modifier.width(4.dp))
}

/** While the keys type into the panel, the header is the box, then Next or Done. */
@Composable
private fun RowScope.TypingHeader(entry: WmPassTextEntry, ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    val masked = entry.field.secret && !ui.showPassword
    BoxPill(Modifier.weight(1f).padding(start = 2.dp)) {
        Icon(
            when (entry.field) {
                WmPassTextField.SEARCH -> Icons.Outlined.Search
                WmPassTextField.PASSWORD, WmPassTextField.NEW_PASSWORD -> Icons.Outlined.Lock
                else -> Icons.Outlined.Key
            },
            contentDescription = null,
            tint = kb.secondaryText,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        SearchQueryText(
            query = if (masked) "•".repeat(entry.text.length) else entry.text,
            placeholder = hintFor(entry.field),
            active = true,
            textColor = kb.chipText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.width(6.dp))
    val next = entry.field == WmPassTextField.NAME || entry.field == WmPassTextField.USERNAME || entry.field == WmPassTextField.PASSWORD
    if (next) {
        ToolPanelChip(stringResource(R.string.ime_wmpass_next)) {
            act(
                WmPassAction.Edit(
                    when (entry.field) {
                        WmPassTextField.NAME -> WmPassTextField.USERNAME
                        WmPassTextField.USERNAME -> WmPassTextField.PASSWORD
                        else -> WmPassTextField.WEBSITE
                    },
                ),
            )
        }
    } else {
        ToolPanelChip(stringResource(R.string.ime_wmpass_done), selected = true) { act(WmPassAction.TextDone) }
    }
    Spacer(Modifier.width(4.dp))
}

// ---- the list ----

@Composable
private fun ItemList(state: KeyboardUiState, ui: WmPassUi, act: (WmPassAction) -> Unit) {
    if (ui.rows.isEmpty()) {
        Message(
            if (ui.query.isBlank()) {
                stringResource(R.string.ime_wmpass_empty)
            } else {
                stringResource(R.string.ime_wmpass_no_match, ui.query.trim())
            },
        )
        return
    }
    val focused = state.focusedIndex(FocusRegion.RESULTS)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        itemsIndexed(ui.rows, key = { _, row -> row.id }) { index, row ->
            ItemRow(row, focused = focused == index, quick = true, act = act)
        }
    }
}

/**
 * One item: its kind, name and the line under it, which opens it. A login
 * also wears its quick chips, which type straight away; opening the tool was
 * the first of the two taps.
 */
@Composable
private fun ItemRow(row: VaultWire.Row, focused: Boolean, quick: Boolean, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .focusRing(focused)
            .clickable { act(WmPassAction.Open(row.id)) }
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindMark(row.kind)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.subtitle.isNotEmpty()) {
                    Text(row.subtitle, color = kb.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (row.matches) {
                Icon(
                    Icons.Outlined.Smartphone,
                    contentDescription = stringResource(R.string.ime_wmpass_matches_desc),
                    tint = kb.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (quick && row.kind == "login" && (row.subtitle.isNotEmpty() || row.hasPassword || row.hasCode)) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.padding(start = 38.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.subtitle.isNotEmpty()) {
                    ToolPanelChip(stringResource(R.string.ime_wmpass_username)) { act(WmPassAction.Type(row.id, VaultWire.USERNAME)) }
                }
                if (row.hasPassword) {
                    ToolPanelChip(stringResource(R.string.ime_wmpass_password)) { act(WmPassAction.Type(row.id, VaultWire.PASSWORD)) }
                }
                if (row.hasCode) {
                    ToolPanelChip(stringResource(R.string.ime_wmpass_code)) { act(WmPassAction.Type(row.id, VaultWire.CODE)) }
                }
            }
        }
    }
}

/** The kind of item as a small round mark, the way WM Pass draws one without its site's icon. */
@Composable
private fun KindMark(kind: String) {
    val kb = LocalKbTheme.current
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(kb.accent.copy(alpha = if (kb.dark) 0.20f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(kindIcon(kind), contentDescription = null, tint = kb.accent, modifier = Modifier.size(16.dp))
    }
}

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "login" -> Icons.Outlined.Key
    "card" -> Icons.Outlined.Payments
    "wifi" -> Icons.Outlined.Wifi
    "note" -> Icons.Outlined.StickyNote2
    "identity" -> Icons.Outlined.Badge
    "ssh" -> Icons.Outlined.Terminal
    "api" -> Icons.Outlined.VpnKey
    "database" -> Icons.Outlined.Storage
    "licence" -> Icons.Outlined.VerifiedUser
    "recovery" -> Icons.Outlined.Restore
    "otp" -> Icons.Outlined.Pin
    "document" -> Icons.Outlined.Description
    else -> Icons.Outlined.Lock
}

// ---- an item ----

/** An item's fields, each with Type and Copy; a secret is named, not shown. */
@Composable
private fun ItemFields(ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    val item = ui.item ?: return
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        itemsIndexed(item.fields, key = { _, field -> field.key }) { _, field ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(labelFor(field), color = kb.secondaryText, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        if (field.secret) DOTS else field.value.orEmpty(),
                        color = kb.suggestionText,
                        fontSize = 14.sp,
                        fontFamily = if (field.secret) FontFamily.Monospace else FontFamily.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                ToolPanelChip(stringResource(R.string.ime_wmpass_type)) { act(WmPassAction.Type(item.id, field.key)) }
                Spacer(Modifier.width(6.dp))
                ToolPanelChip(stringResource(R.string.ime_wmpass_copy), icon = Icons.Outlined.ContentCopy) { act(WmPassAction.Copy(item.id, field.key)) }
            }
        }
        if (item.changes != null) {
            item(key = "change") {
                Row(Modifier.padding(vertical = 8.dp)) {
                    ToolPanelChip(stringResource(R.string.ime_wmpass_change_password)) { act(WmPassAction.StartChange) }
                }
            }
        }
    }
}

/** A field's name: this keyboard's own words for what every item can hold, WM Pass's for its kinds' fields. */
@Composable
private fun labelFor(field: VaultWire.Field): String = when {
    field.key == VaultWire.CODE -> stringResource(R.string.ime_wmpass_code)
    field.key == VaultWire.NOTES -> stringResource(R.string.ime_wmpass_field_notes)
    field.key.startsWith(VaultWire.SITE) -> stringResource(R.string.ime_wmpass_field_website)
    else -> field.label
}

// ---- the forms ----

/** A new login, laid out whole while no box has the keys: a tap on a line types into it. */
@Composable
private fun AddForm(ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
        for (field in listOf(WmPassTextField.NAME, WmPassTextField.USERNAME, WmPassTextField.PASSWORD, WmPassTextField.WEBSITE)) {
            FormLine(field, ui.draft.of(field), ui.showPassword) { act(WmPassAction.Edit(field)) }
        }
        Spacer(Modifier.height(6.dp))
        FormChips(ui, act)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ime_wmpass_new_login_info), color = kb.secondaryText, fontSize = 11.sp, maxLines = 2)
    }
}

/** A new password for the open item, the same way. */
@Composable
private fun ChangeForm(ui: WmPassUi, act: (WmPassAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
        FormLine(WmPassTextField.NEW_PASSWORD, ui.newPassword, ui.showPassword) { act(WmPassAction.Edit(WmPassTextField.NEW_PASSWORD)) }
        Spacer(Modifier.height(6.dp))
        FormChips(ui, act)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ime_wmpass_new_password_info), color = kb.secondaryText, fontSize = 11.sp, maxLines = 2)
    }
}

@Composable
private fun FormLine(field: WmPassTextField, text: String, shown: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(nameOf(field), color = kb.secondaryText, fontSize = 12.sp, modifier = Modifier.width(92.dp), maxLines = 1)
        Text(
            when {
                text.isEmpty() -> stringResource(R.string.ime_wmpass_not_set)
                field.secret && !shown -> "•".repeat(text.length)
                else -> text
            },
            color = if (text.isEmpty()) kb.secondaryText else kb.suggestionText,
            fontSize = 14.sp,
            fontFamily = if (field.secret && text.isNotEmpty()) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Under a form: a password from WM Pass's generator, Show or Hide, Save. */
@Composable
private fun FormChips(ui: WmPassUi, act: (WmPassAction) -> Unit, typing: WmPassTextField? = null) {
    val secretInView = typing == null || typing.secret
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (secretInView) {
            ToolPanelChip(stringResource(R.string.ime_wmpass_generate)) { act(WmPassAction.Generate) }
            ToolPanelChip(
                stringResource(if (ui.showPassword) R.string.ime_wmpass_hide else R.string.ime_wmpass_show),
                icon = if (ui.showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            ) { act(WmPassAction.ToggleShow) }
        }
        if (typing != null) {
            ToolPanelChip(stringResource(R.string.ime_wmpass_save), selected = true, enabled = canSave(ui)) { act(WmPassAction.Save) }
        }
    }
}

/**
 * Under the box while it types. A search shows what it finds; a form shows
 * its other boxes, to jump to, and what can be done to this one.
 */
@Composable
private fun TypingBody(entry: WmPassTextEntry, ui: WmPassUi, act: (WmPassAction) -> Unit) {
    if (entry.field == WmPassTextField.SEARCH) {
        if (ui.rows.isEmpty() && ui.query.isNotBlank()) {
            Message(stringResource(R.string.ime_wmpass_no_match, ui.query.trim()))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                itemsIndexed(ui.rows, key = { _, row -> row.id }) { _, row -> ItemRow(row, focused = false, quick = false, act = act) }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (ui.page == WmPassPage.ADD) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (field in listOf(WmPassTextField.NAME, WmPassTextField.USERNAME, WmPassTextField.PASSWORD, WmPassTextField.WEBSITE)) {
                    ToolPanelChip(nameOf(field), selected = field == entry.field) { act(WmPassAction.Edit(field)) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        FormChips(ui, act, typing = entry.field)
    }
}

/** Whether Save has something to send: any line of a new login, or a new password. */
private fun canSave(ui: WmPassUi): Boolean = !ui.busy && when (ui.page) {
    WmPassPage.ADD -> !ui.draft.blank
    WmPassPage.CHANGE -> ui.newPassword.isNotEmpty()
    else -> false
}

@Composable
private fun nameOf(field: WmPassTextField): String = when (field) {
    WmPassTextField.NAME -> stringResource(R.string.ime_wmpass_field_name)
    WmPassTextField.USERNAME -> stringResource(R.string.ime_wmpass_username)
    WmPassTextField.PASSWORD, WmPassTextField.NEW_PASSWORD -> stringResource(R.string.ime_wmpass_password)
    WmPassTextField.WEBSITE -> stringResource(R.string.ime_wmpass_field_website)
    WmPassTextField.SEARCH -> stringResource(R.string.ime_wmpass_search_hint)
}

@Composable
private fun hintFor(field: WmPassTextField): String = when (field) {
    WmPassTextField.SEARCH -> stringResource(R.string.ime_wmpass_search_hint)
    WmPassTextField.NAME -> stringResource(R.string.ime_wmpass_name_hint)
    WmPassTextField.USERNAME -> stringResource(R.string.ime_wmpass_username_hint)
    WmPassTextField.PASSWORD -> stringResource(R.string.ime_wmpass_password)
    WmPassTextField.WEBSITE -> stringResource(R.string.ime_wmpass_website_hint)
    WmPassTextField.NEW_PASSWORD -> stringResource(R.string.ime_wmpass_new_password_hint)
}

// ---- parts ----

/** A rounded box in the chips' look, holding a search or the box being typed in. */
@Composable
private fun BoxPill(modifier: Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Row(
        modifier = modifier
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun NoticeLine(notice: WmPassNotice, modifier: Modifier) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Text(
        stringResource(
            when (notice) {
                WmPassNotice.COPIED -> R.string.ime_wmpass_copied
                WmPassNotice.COPY_FAILED -> R.string.ime_wmpass_copy_failed
                WmPassNotice.TYPE_FAILED -> R.string.ime_wmpass_type_failed
                WmPassNotice.SAVED -> R.string.ime_wmpass_saved
                WmPassNotice.SAVE_FAILED -> R.string.ime_wmpass_save_failed
                WmPassNotice.CHANGED -> R.string.ime_wmpass_changed
                WmPassNotice.CHANGE_FAILED -> R.string.ime_wmpass_change_failed
                WmPassNotice.NO_PASSWORD -> R.string.ime_wmpass_no_password
                WmPassNotice.GONE -> R.string.ime_wmpass_gone
            },
        ),
        color = kb.chipText,
        fontSize = 12.sp,
        maxLines = 2,
        modifier = modifier
            .padding(10.dp)
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun StatusMessage(status: WmPassBridge.Status?, act: (WmPassAction) -> Unit) {
    when (status) {
        WmPassBridge.Status.LOCKED, WmPassBridge.Status.NO_VAULT -> Message(
            stringResource(if (status == WmPassBridge.Status.LOCKED) R.string.ime_wmpass_locked else R.string.ime_wmpass_no_vault),
            stringResource(R.string.ime_wmpass_open),
            { act(WmPassAction.OpenApp) },
            { act(WmPassAction.Refresh) },
        )
        WmPassBridge.Status.OFF -> Message(
            stringResource(R.string.ime_wmpass_off),
            stringResource(R.string.ime_wmpass_open),
            { act(WmPassAction.OpenApp) },
            { act(WmPassAction.Refresh) },
        )
        WmPassBridge.Status.UNTRUSTED -> Message(stringResource(R.string.ime_wmpass_untrusted))
        else -> Message(stringResource(R.string.ime_wmpass_missing))
    }
}

@Composable
private fun Message(
    words: String,
    action: String? = null,
    onAction: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(words, color = kb.suggestionText, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (action != null) ToolPanelChip(action, selected = false, onClick = onAction)
            if (onRetry != null) ToolPanelChip(stringResource(R.string.ime_wmpass_retry), selected = false, onClick = onRetry)
        }
    }
}

package com.wasimaster.wmkeyboard.core.settings

import android.text.InputType
import com.wasimaster.wmkeyboard.core.tools.BuiltInSymbolSets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

/**
 * The rows stacked around the keys, in top-to-bottom order, with [KEYBOARD]
 * standing for the keys themselves: rows before it draw above the keys, rows
 * after it draw below (issue #83). [TOPBAR] is the suggestion/toolbar strip;
 * the emoji and symbol rows only render when their settings turn them on,
 * [FANCY] (the Fancy Text style strip) only while the fancy layout is active,
 * [TOOLS] (the tools on a row of their own) only under an own-row
 * `ToolbarPlacement`, and [DICTIONARY] (the dictionary bar, issue #51) when
 * `RowSettings` turns it on — but every row keeps its slot in the order
 * either way.
 *
 * [MACROS] (the selection macros, off by default) is the one row that follows
 * neither a setting alone nor the layout: it appears only while there is a
 * selection to act on, and only under the own-row placement.
 *
 * [STICKERS] (your own stickers offered while typing, #329) follows state the
 * same way: it opens when the text before the cursor asks for a sticker, and
 * has a slot only while that feature is switched on.
 *
 * Stored by name. The reader drops a name it does not know and
 * [sanitizeBarOrder] fills the gap, so a build that predates a constant
 * still decodes an order written by a newer one.
 */
enum class BarRow { TOPBAR, EMOJI, SYMBOL, FANCY, TOOLS, DICTIONARY, MACROS, STICKERS, RECENT, KEYBOARD }

/**
 * The shipped stacking: emoji on top because it is reached for most, the
 * tools row directly over the strip whose chevron opens it, the style strip
 * last, next to the keys whose letters it changes.
 *
 * The macro row goes directly above the strip, under the tools: it is the row
 * that comes and goes with a selection, so it belongs where it pushes the
 * fewest fixed rows around when it arrives.
 *
 * The sticker tray goes above everything: it is an offer that comes and goes
 * with what is typed, and on top it pushes no fixed row around under the
 * finger that is about to tap it. An order stored before it existed gets it
 * on top too, since [sanitizeBarOrder] slots a missing row above the first
 * row that follows it in this list.
 *
 * The recent words (#894) go directly above the strip, where they drew before
 * they were a row of their own; [sanitizeBarOrder] puts them there for an
 * order stored before they were.
 *
 * The keys come last, so every row sits above them — which is also where
 * [sanitizeBarOrder] puts the keys for an order stored before they were an
 * entry, keeping that user's keyboard exactly as it was.
 */
val DefaultBarOrder: List<BarRow> = listOf(
    BarRow.STICKERS, BarRow.EMOJI, BarRow.TOOLS, BarRow.MACROS, BarRow.RECENT, BarRow.TOPBAR, BarRow.SYMBOL,
    BarRow.DICTIONARY, BarRow.FANCY, BarRow.KEYBOARD,
)

/**
 * Ensures every row appears exactly once, preserving the stored order.
 *
 * A row the stored order does not know goes where [DefaultBarOrder] puts it
 * relative to the rows that *are* there: just above the first present row
 * that follows it in the default, or at the bottom when none does. So an
 * order saved before the tools row existed gets it over the strip, where
 * the row already drew, rather than under the keys' nose.
 */
fun sanitizeBarOrder(rows: List<BarRow>): List<BarRow> {
    val out = rows.distinct().toMutableList()
    for (missing in DefaultBarOrder.filter { it !in out }) {
        val successors = DefaultBarOrder.drop(DefaultBarOrder.indexOf(missing) + 1)
        val anchor = out.indexOfFirst { it in successors }
        if (anchor < 0) out.add(missing) else out.add(anchor, missing)
    }
    return out
}

/**
 * The rows that draw above the keys: everything before [BarRow.KEYBOARD].
 * An order with no keyboard entry (never after [sanitizeBarOrder], but the
 * split must not depend on it) puts every row above, as it always did.
 */
fun barRowsAboveKeys(order: List<BarRow>): List<BarRow> {
    val keys = order.indexOf(BarRow.KEYBOARD)
    return if (keys < 0) order else order.subList(0, keys)
}

/** The rows that draw below the keys: everything after [BarRow.KEYBOARD]. */
fun barRowsBelowKeys(order: List<BarRow>): List<BarRow> {
    val keys = order.indexOf(BarRow.KEYBOARD)
    return if (keys < 0) emptyList() else order.subList(keys + 1, order.size)
}

/**
 * Kind of input field a keyboard mode can bind to, derived from
 * EditorInfo.inputType (named ModeField to stay distinct from the IME's
 * layout-level FieldKind). A field can match several bindings at once — a
 * password box in a browser matches the app binding and [PASSWORD].
 *
 * [TEXT] is the ordinary free-text box: everything that is not a password,
 * email, URL, number or phone field. It is what a chat composer or a
 * document body is, and it is deliberately the *narrow* option — binding a
 * mode to an app plus [TEXT] keeps that mode off the app's search boxes,
 * address fields and login forms.
 *
 * [NOTIFICATION_REPLY] is the inline reply box in a notification. That box
 * is hosted by the system UI, so the package the IME sees is the shell's,
 * never the app whose notification it is — which is why this is a field
 * kind and why it matches without an app binding (see [matchesField]).
 *
 * The last three describe the *shape* of a text box rather than what goes in
 * it, and a field reports one of them on top of its kind (see
 * [textShapeFields]). They exist for apps whose fields all look alike to the
 * inputType classes above — a file manager's rename box, text editor and code
 * editor are all plain text (issue #186), but only the editors are
 * [MULTILINE], and only the code editor asks for [NO_SUGGESTIONS].
 * [SINGLE_LINE] is every one-line text box: a rename dialog, a title, a search
 * field. Password boxes are excluded from all three for the reason [TEXT]
 * excludes them.
 */
enum class ModeField {
    PASSWORD, EMAIL, URL, NUMBER, PHONE, TEXT, NOTIFICATION_REPLY,
    MULTILINE, SINGLE_LINE, NO_SUGGESTIONS,
}

/**
 * The shape bindings a text field reports: [ModeField.MULTILINE] or
 * [ModeField.SINGLE_LINE] from the multi-line flag, plus
 * [ModeField.NO_SUGGESTIONS] when the field asked the IME to keep its
 * suggestions to itself. Empty for anything that is not a text-class field
 * (a keypad has no shape worth binding to) and for password boxes.
 */
fun textShapeFields(inputType: Int, secure: Boolean): Set<ModeField> {
    if (secure) return emptySet()
    if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return emptySet()
    return buildSet {
        if (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) {
            add(ModeField.MULTILINE)
        } else {
            add(ModeField.SINGLE_LINE)
        }
        if (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) {
            add(ModeField.NO_SUGGESTIONS)
        }
    }
}

/**
 * One keyboard mode: a named bundle of overrides applied while the mode is
 * active. Null fields inherit the global setting. A mode activates
 * automatically for its bound apps ([apps], package names) and/or field
 * kinds ([fieldKinds]), or manually from the Modes tool — a manual pick
 * wins until the user switches to another app.
 *
 * Binding both apps and field kinds means *both* have to match (see
 * [matchesField]): the "Chat" mode is bound to the messaging apps **and**
 * [ModeField.TEXT], so it switches on for the message composer but not for
 * the app's contact search. [hints] is a third condition of the same kind.
 */
@Serializable
data class KeyboardMode(
    val id: String,
    val name: String,
    /**
     * Id of the icon shown next to the mode's name, from `ModeIcons.catalog`.
     * An id rather than a drawable so stored modes survive the catalog being
     * reshuffled; null and unknown ids fall back to the generic mode icon.
     */
    val icon: String? = null,
    /** Emoji row presentation while active; null inherits the global choice. */
    val emojiBarMode: EmojiBarMode? = null,
    /**
     * Theme worn while this mode is active; null inherits whatever is set.
     *
     * A hard override, not a preference: it wins over the hand-picked theme
     * *and* over an enabled auto-theme pair, because a mode saying "look like
     * this" is only meaningful if it actually does. Scoped to the mode — the
     * global choice is untouched and comes straight back when the mode ends.
     *
     * The id namespace is [KeyboardSettings.keyboardThemeId]'s: [DEFAULT_THEME_ID],
     * a built-in id, or a custom one. An id whose theme has since been deleted
     * resolves to the default, as it does everywhere else.
     */
    val themeId: String? = null,
    /**
     * Pinned toolbar tools while active; null inherits the user's toolbar.
     * Replaces the toolbar unless [toolbarToolsAppend] is on.
     */
    val toolbarTools: List<ToolbarTool>? = null,
    /**
     * When true, [toolbarTools] are added after the user's own pinned tools
     * instead of replacing them — the mode contributes its specialty tools
     * without taking away the ones the user pinned by hand.
     */
    val toolbarToolsAppend: Boolean = false,
    /**
     * Tools to float to the front of the toolbox panel while active; null
     * inherits the user's order. A partial list is enough — anything not
     * named keeps its global rank behind these.
     */
    val toolboxOrder: List<ToolbarTool>? = null,
    /** Symbol row on/off while active; null inherits. */
    val symbolRowEnabled: Boolean? = null,
    /**
     * Symbol sets offered by the row's picker while active (first one is
     * the default); null inherits the globally enabled sets.
     */
    val symbolSetIds: List<String>? = null,
    /**
     * Autocorrect while active; null inherits.
     *
     * The mode system overrode the theme, the toolbar and the rows but nothing
     * about typing, so Coding mode still corrected identifiers into English
     * words. That is the one thing a mode for a code editor exists to stop.
     */
    val autocorrect: Boolean? = null,
    /** Automatic capitalisation while active; null inherits. */
    val autoCapitalize: Boolean? = null,
    /** Suggestions while active; null inherits. */
    val suggestions: Boolean? = null,
    /**
     * Every space the keyboard types by itself while active; null inherits.
     *
     * One switch over the three settings that put a space in without a press:
     * after punctuation, after a suggestion picked from the strip, and after a
     * glided word — and over the space *before* a glided word, which has no
     * switch of its own (#184). They are one question to the person typing — "does this
     * field want spaces I did not ask for?" — and the answer is per field, not
     * per source of the space. Off means every space in the field is one the
     * user typed, which is what a filename, a renamed extension or a terminal
     * line needs (issue #124).
     *
     * On turns all three on, including the space after punctuation, which is
     * off globally by default: a mode saying "space things for me" that left
     * that one alone would be saying it only two thirds of the time.
     */
    val autoSpace: Boolean? = null,
    /**
     * Glide typing while active; null inherits.
     *
     * With every other typing switch off, a mode still could not stop a tap
     * that drifts from starting a swipe (#738), so a field meant for codes,
     * handles or slang never typed exactly the keys pecked (#753).
     */
    val glide: Boolean? = null,
    /**
     * Two-thumb typing while active; null inherits. Off stops All thumbs up
     * from ending a tapped word with a space nobody pressed (#734, #753).
     */
    val twoThumb: Boolean? = null,
    /**
     * Every key goes straight into the field while active, committed, with no
     * word held back to complete or correct; null inherits (#1024). What an SSH
     * client or a code editor with a real text field wants, and what a TYPE_NULL
     * terminal already gets. A first cut: the strip has no word to read here, so
     * it offers no completions.
     */
    val commitAsTyped: Boolean? = null,
    /**
     * Layout worn while this mode is active; null inherits whichever layout the
     * user last switched to.
     *
     * A hard override in the same sense [themeId] is, and for the same reason:
     * a bilingual user pinning a Latin layout to their code editor means it,
     * and the switch ring is untouched — the layout they were on comes back
     * when the mode ends.
     *
     * The id namespace is [KeyboardSettings.enabledLayoutIds]'. A layout that
     * has since been removed or switched off resolves to no override, which is
     * the same thing null means.
     */
    val layoutId: String? = null,
    /** Package names this mode activates for automatically. */
    val apps: List<String> = emptyList(),
    /** Input-field kinds this mode activates for automatically. */
    val fieldKinds: List<ModeField> = emptyList(),
    /**
     * Placeholder texts this mode activates for: the field's hint (the grey
     * text an empty box shows) has to contain one of them, case ignored. The
     * hint is the one thing an app writes on a field that names what the
     * field is *for* — a file manager's rename box says "Enter name" or
     * "Extension" where its inputType says only "text" (issue #186). It is
     * also the app's own wording in the app's own language, so a binding
     * written for one locale is silent in another; that is the trade.
     */
    val hints: List<String> = emptyList(),
    /**
     * Whether the keyboard may use this mode at all. Off, the mode never
     * activates on its own and the Modes tool does not offer it, but every
     * binding and override it holds is kept, so it can be switched back on as
     * it was. Deleting was the only way to silence a mode before, and a mode
     * is a screenful of setup nobody wants to rebuild for a week without it
     * (issue #152). Stored modes from before the switch decode as on.
     */
    val enabled: Boolean = true,
) {
    /**
     * Whether this mode prescribes a tool arrangement of its own. When it
     * does, a drag made while it is active has to be written back into the
     * mode — stored globally it would be overwritten by the mode's list on
     * the very next composition and look like it never happened.
     */
    val ownsToolOrder: Boolean get() = toolbarTools != null || toolboxOrder != null
}

private val modeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

object KeyboardModeCodec {
    fun encodeList(modes: List<KeyboardMode>): String = modeJson.encodeToString(modes)

    fun decodeList(json: String): List<KeyboardMode> {
        // See [LayoutCodec.decodeList] — blank is normal, not an error worth
        // an exception on every settings emission.
        if (json.isBlank()) return emptyList()
        // One mode at a time. Decoding the list whole meant a single mode the
        // build could not read (a downgrade, a hand-edited backup) dropped
        // every mode, and the next edit wrote that empty list back for good.
        val elements = runCatching { modeJson.parseToJsonElement(json).jsonArray }
            .getOrNull() ?: return DefaultKeyboardModes
        val modes = elements.mapNotNull { element ->
            runCatching { modeJson.decodeFromJsonElement<KeyboardMode>(element) }.getOrNull()
        }
        // Something was stored and none of it survived: the shipped modes
        // beat an empty screen the user never asked for.
        if (modes.isEmpty() && elements.isNotEmpty()) return DefaultKeyboardModes
        return modes.map(::migrateMode)
    }

    /** Built-in modes missing from [modes], in their shipped order. */
    fun missingDefaults(modes: List<KeyboardMode>): List<KeyboardMode> {
        val have = modes.map { it.id }.toSet()
        return DefaultKeyboardModes.filter { it.id !in have }
    }

    /** App list the browser mode used to ship with (now field-bound only). */
    private val legacyBrowserApps = listOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.sec.android.app.sbrowser",
        "com.duckduckgo.mobile.android",
    )

    /** App list the email mode used to ship with (now field-bound only). */
    private val legacyEmailApps = listOf(
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.yahoo.mobile.client.android.mail",
        "ch.protonmail.android",
        "com.fsck.k9",
    )

    /**
     * Stored copies of a seeded mode keep their old app bindings forever;
     * drop them when they are exactly the old default so the mode becomes
     * field-bound only like a fresh install. A user-edited app list is left
     * alone.
     *
     * The email mode loses its app list because bindings are now an AND:
     * keeping the apps would have narrowed it to email boxes *inside* those
     * five apps, when an email box anywhere is what it is for. The mail
     * apps live on in the Writing mode instead, which handles their bodies.
     */
    private fun migrateMode(mode: KeyboardMode): KeyboardMode = when {
        mode.id == "mode_browser" && mode.apps == legacyBrowserApps ->
            mode.copy(apps = emptyList()).withSeededIcon()
        mode.id == "mode_email" && mode.apps == legacyEmailApps ->
            mode.copy(apps = emptyList()).withSeededIcon()
        else -> mode.withSeededIcon()
    }

    /**
     * Modes stored before icons existed have none, so a seeded mode would
     * show the generic fallback forever. Fill in the icon it ships with —
     * only when there is none, so a user's pick is never overwritten.
     */
    private fun KeyboardMode.withSeededIcon(): KeyboardMode =
        if (icon != null) {
            this
        } else {
            DefaultKeyboardModes.firstOrNull { it.id == id }?.icon
                ?.let { copy(icon = it) } ?: this
        }
}

/** Messaging and social apps the Chat mode ships bound to. */
private val ChatApps = listOf(
    "com.facebook.orca",
    "com.instagram.android",
    "org.telegram.messenger",
    "com.whatsapp",
    "org.thoughtcrime.securesms",
    "com.discord",
    "com.facebook.katana",
    "com.facebook.lite",
    "com.zhiliaoapp.musically",
    "com.snapchat.android",
    "com.viber.voip",
    "jp.naver.line.android",
    "com.kakao.talk",
    "com.tencent.mm",
    "com.beeper.android",
)

/**
 * Writing and note-taking apps the Writing mode ships bound to. The mail
 * apps belong here too: the mode only fires on plain text fields, and in a
 * mail app that is the body — the recipient boxes are email fields and go
 * to the Email mode.
 */
private val WritingApps = listOf(
    // Notes and documents
    "com.google.android.keep",
    "com.google.android.apps.docs.editors.docs",
    "com.samsung.android.app.notes",
    "com.microsoft.office.word",
    "com.microsoft.office.onenote",
    "md.obsidian",
    "notion.id",
    "com.evernote",
    "net.cozic.joplin",
    // Mail (bodies only — see above)
    "com.google.android.gm",
    "com.microsoft.office.outlook",
    "com.yahoo.mobile.client.android.mail",
    "ch.protonmail.android",
    "com.fsck.k9",
)

/**
 * Starter modes seeded on first run (stored copies — the user can edit or
 * delete them freely). App lists cover the mainstream apps per category;
 * binding extra apps is one tap in the mode editor.
 */
val DefaultKeyboardModes: List<KeyboardMode> = listOf(
    KeyboardMode(
        id = "mode_password",
        icon = "lock",
        name = "Passwords",
        emojiBarMode = EmojiBarMode.OFF,
        // The one mode that deliberately *replaces* the toolbar: nothing
        // that opens a network panel belongs next to a password box.
        toolbarTools = listOf(
            ToolbarTool.PASSWORD_GEN, ToolbarTool.CLIPBOARD, ToolbarTool.SETTINGS,
        ),
        toolboxOrder = listOf(
            ToolbarTool.PASSWORD_GEN, ToolbarTool.CLIPBOARD, ToolbarTool.SNIPPETS,
            ToolbarTool.NUMPAD, ToolbarTool.SYMBOLS, ToolbarTool.SETTINGS,
        ),
        symbolRowEnabled = false,
        fieldKinds = listOf(ModeField.PASSWORD),
    ),
    // Field-bound only: an email box is an email box in any app. The mail
    // apps are bound by the Writing mode, which covers their bodies.
    KeyboardMode(
        id = "mode_email",
        icon = "mail",
        name = "Email",
        toolboxOrder = listOf(
            ToolbarTool.CLIPBOARD, ToolbarTool.SNIPPETS, ToolbarTool.TEXT_EDIT,
            ToolbarTool.VOICE, ToolbarTool.SYMBOLS,
        ),
        symbolRowEnabled = true,
        symbolSetIds = listOf(BuiltInSymbolSets.EMAIL_ID, BuiltInSymbolSets.PUNCTUATION_ID),
        fieldKinds = listOf(ModeField.EMAIL),
    ),
    // Field-bound only: most typing in a browser is ordinary text (search
    // boxes, forms, web apps) — the web symbol set should kick in just for
    // the address bar, so no app bindings here.
    KeyboardMode(
        id = "mode_browser",
        icon = "web",
        name = "Browser",
        toolboxOrder = listOf(
            ToolbarTool.CLIPBOARD, ToolbarTool.WEB_SEARCH, ToolbarTool.SNIPPETS,
            ToolbarTool.QR_SCAN, ToolbarTool.VOICE, ToolbarTool.INCOGNITO,
        ),
        symbolRowEnabled = true,
        symbolSetIds = listOf(BuiltInSymbolSets.WEB_ID),
        fieldKinds = listOf(ModeField.URL),
    ),
    KeyboardMode(
        id = "mode_chat",
        icon = "chat",
        name = "Chat",
        // The emoji row earns its space in a chat: it is the whole point.
        emojiBarMode = EmojiBarMode.ALWAYS,
        // What you reach for mid-sentence. Grammar is a once-per-message
        // check, so it leads the toolbox instead of taking toolbar space.
        toolbarTools = listOf(
            ToolbarTool.EMOJI, ToolbarTool.GIF, ToolbarTool.STICKER, ToolbarTool.VOICE,
        ),
        toolbarToolsAppend = true,
        toolboxOrder = listOf(
            ToolbarTool.GRAMMAR, ToolbarTool.TRANSLATE, ToolbarTool.CAMERA,
            ToolbarTool.IMAGE_SEARCH, ToolbarTool.CLIPBOARD, ToolbarTool.SNIPPETS,
            // The pinned four, ranked in case the user unpins one — the
            // toolbox skips whatever is on the toolbar, so these sit idle
            // until then.
            ToolbarTool.EMOJI, ToolbarTool.GIF, ToolbarTool.STICKER, ToolbarTool.VOICE,
        ),
        apps = ChatApps,
        // NOTIFICATION_REPLY rides along so the inline reply box in the
        // notification shade — hosted by the system UI, so the app binding
        // can never match there — still gets the chat treatment.
        fieldKinds = listOf(ModeField.TEXT, ModeField.NOTIFICATION_REPLY),
    ),
    KeyboardMode(
        id = "mode_writing",
        icon = "write",
        name = "Writing",
        // A self-contained toolbar — it replaces the user's pins rather than
        // appending, so the Settings shortcut the global pins carry never
        // rides along into a writing session. Writing helpers lead (compose
        // and edit with AI, the caret tools, dictation), then the everyday
        // paste-and-insert pins. AI drops out on its own in a build without
        // it (see isSupportedTool), leaving the rest of the bar untouched.
        toolbarTools = listOf(
            ToolbarTool.AI, ToolbarTool.TEXT_EDIT, ToolbarTool.VOICE,
            ToolbarTool.CLIPBOARD, ToolbarTool.EMOJI,
        ),
        toolbarToolsAppend = false,
        toolboxOrder = listOf(
            // Polish the draft, then pull material into it, then the
            // reference and history tools.
            ToolbarTool.GRAMMAR, ToolbarTool.DICTIONARY, ToolbarTool.VOCABULARY,
            ToolbarTool.OCR, ToolbarTool.DOC_SCAN, ToolbarTool.CALENDAR,
            ToolbarTool.CLIPBOARD, ToolbarTool.SNIPPETS, ToolbarTool.TRANSLATE,
            ToolbarTool.UNDO, ToolbarTool.REDO,
            // Ranked for whenever the user unpins one of the pinned tools above.
            ToolbarTool.AI, ToolbarTool.TEXT_EDIT, ToolbarTool.VOICE, ToolbarTool.EMOJI,
            ToolbarTool.TRACKPAD,
        ),
        symbolRowEnabled = true,
        symbolSetIds = listOf(BuiltInSymbolSets.PUNCTUATION_ID),
        apps = WritingApps,
        fieldKinds = listOf(ModeField.TEXT),
    ),
    KeyboardMode(
        id = "mode_coding",
        icon = "code",
        name = "Coding",
        toolboxOrder = listOf(
            ToolbarTool.SNIPPETS, ToolbarTool.CLIPBOARD, ToolbarTool.TEXT_EDIT,
            ToolbarTool.TRACKPAD, ToolbarTool.SYMBOLS, ToolbarTool.CALCULATOR, ToolbarTool.NUMPAD,
            ToolbarTool.UNDO, ToolbarTool.REDO,
            ToolbarTool.CURSOR_LEFT, ToolbarTool.CURSOR_RIGHT,
            ToolbarTool.CURSOR_HOME, ToolbarTool.CURSOR_END,
        ),
        symbolRowEnabled = true,
        symbolSetIds = listOf(BuiltInSymbolSets.CODING_ID, BuiltInSymbolSets.MATH_ID),
        apps = listOf(
            "com.termux",
            "com.foxdebug.acode",
            "com.rk.xededitor",
            "io.spck",
        ),
    ),
)

/**
 * How many rounds of default modes exist. Bump this and add the new ids to
 * a `ModesAddedInSeedVersionN` list whenever [DefaultKeyboardModes] grows,
 * so upgrades pick the new modes up — see
 * `SettingsRepository.seedNewDefaultModes`.
 */
const val CurrentModeSeedVersion: Int = 3

/** Default modes added in seed version 2 (chat and writing). */
val ModesAddedInSeedVersion2: Set<String> = setOf("mode_chat", "mode_writing")

/** Apps appended to already-stored default modes in seed version 3. */
val ModeAppsAddedInSeedVersion3: Map<String, List<String>> = mapOf(
    "mode_chat" to listOf(
        "com.facebook.katana",
        "com.facebook.lite",
        "com.zhiliaoapp.musically",
        "com.snapchat.android",
        "com.viber.voip",
        "jp.naver.line.android",
        "com.kakao.talk",
        "com.tencent.mm",
        "com.beeper.android",
    ),
)

/** Field kinds appended to already-stored default modes in seed version 3. */
val ModeFieldsAddedInSeedVersion3: Map<String, List<ModeField>> = mapOf(
    "mode_chat" to listOf(ModeField.NOTIFICATION_REPLY),
)

/**
 * Appends [additions] (mode id to packages) to the stored modes. A package
 * already bound to *any* stored mode is skipped: the user (or an earlier
 * seed) already routed it, and a package only sensibly drives one mode. A
 * mode the user deleted is simply absent and gets nothing.
 */
fun topUpModeApps(
    stored: List<KeyboardMode>,
    additions: Map<String, List<String>>,
): List<KeyboardMode> {
    val bound = stored.flatMapTo(HashSet()) { it.apps }
    return stored.map { mode ->
        val extra = additions[mode.id].orEmpty().filter { it !in bound }
        if (extra.isEmpty()) mode else mode.copy(apps = mode.apps + extra)
    }
}

/** [topUpModeApps] for field kinds, minus the cross-mode check — a field
 *  kind can drive several modes, so only same-mode duplicates are skipped. */
fun topUpModeFields(
    stored: List<KeyboardMode>,
    additions: Map<String, List<ModeField>>,
): List<KeyboardMode> = stored.map { mode ->
    val extra = additions[mode.id].orEmpty().filter { it !in mode.fieldKinds }
    if (extra.isEmpty()) mode else mode.copy(fieldKinds = mode.fieldKinds + extra)
}

/**
 * Whether the focused field satisfies this mode's automatic bindings.
 *
 * Apps and field kinds are an AND when both are set: binding the Chat mode
 * to WhatsApp *and* [ModeField.TEXT] keeps it on the message composer and
 * off the contact search. Setting only one of the two matches on that one
 * alone; setting neither makes the mode manual-only.
 *
 * [hints] is a third condition on the same terms: when set, the field's
 * hint text has to contain one of them (case ignored), on its own or on top
 * of the other two.
 *
 * [ModeField.NOTIFICATION_REPLY] is the one exception to the AND: the
 * inline reply box lives in the system UI, whose package is never the app
 * the user is replying to, so a mode bound to that field matches it with
 * the app bindings ignored. A hint binding still has to hold there.
 */
private fun KeyboardMode.matchesField(
    packageName: String?,
    fields: Set<ModeField>,
    hint: String?,
): Boolean {
    val hintMatch = hints.isEmpty() || matchesHint(hint)
    if (ModeField.NOTIFICATION_REPLY in fields &&
        ModeField.NOTIFICATION_REPLY in fieldKinds
    ) {
        return hintMatch
    }
    val appMatch = packageName != null && packageName in apps
    val fieldMatch = fieldKinds.any { it in fields }
    val bound = when {
        apps.isEmpty() && fieldKinds.isEmpty() -> hints.isNotEmpty()
        apps.isEmpty() -> fieldMatch
        fieldKinds.isEmpty() -> appMatch
        else -> appMatch && fieldMatch
    }
    return bound && hintMatch
}

/** Whether [hint] contains one of this mode's [KeyboardMode.hints], case ignored. */
private fun KeyboardMode.matchesHint(hint: String?): Boolean {
    if (hint.isNullOrBlank()) return false
    return hints.any { it.isNotBlank() && hint.contains(it.trim(), ignoreCase = true) }
}

/**
 * The settings with the modes the keyboard must not use taken out of the
 * picture, applied on the way out of the repository exactly as
 * `underPowerSaving` and `restrictedToDirectBoot` are: a view, never a write
 * (issue #41).
 *
 * Trimming [KeyboardSettings.keyboardModes] is the whole gate. Every reader of
 * modes — [resolveKeyboardMode], the tool's panel, the per-mode theme lookup —
 * goes through that list, so none of them needs to learn that either switch
 * exists. With the feature off the list is emptied and the tool goes with it:
 * a Modes button that opens an empty panel is worse than no button. With the
 * feature on, a mode switched off by itself ([KeyboardMode.enabled], #152) is
 * simply not in the list. The user's modes stay stored either way, so
 * switching back on brings every one of them back.
 */
fun KeyboardSettings.withoutModes(): KeyboardSettings =
    if (modesEnabled) {
        if (keyboardModes.all { it.enabled }) this
        else copy(keyboardModes = keyboardModes.filter { it.enabled })
    } else {
        copy(
            keyboardModes = emptyList(),
            toolbarTools = toolbarTools - ToolbarTool.MODES,
            enabledTools = enabledTools - ToolbarTool.MODES,
        )
    }

/**
 * The modes that draw [row] differently from the global setting while they are
 * active, in list order — what the settings app warns about next to the row's
 * own switch. Switching the symbol row off globally does nothing in a field the
 * Email mode takes over, since the mode turns it back on; without the warning
 * that reads as a switch that does not work.
 *
 * Only the rows a mode can override are answered: the emoji row and the symbol
 * row. Every other row returns empty. A mode that sets the row to the value the
 * global setting already has is no override worth naming, and modes the
 * keyboard is not using ([withoutModes]) never activate, so neither is listed.
 */
fun KeyboardSettings.modesOverridingRow(row: BarRow): List<KeyboardMode> {
    val modes = withoutModes().keyboardModes
    return when (row) {
        BarRow.EMOJI -> modes.filter { it.emojiBarMode != null && it.emojiBarMode != emojiBarMode }
        BarRow.SYMBOL -> modes.filter {
            it.symbolRowEnabled != null && it.symbolRowEnabled != symbolRowEnabled
        }
        else -> emptyList()
    }
}

/**
 * Manual pick meaning "no mode at all": the Modes tool's None row. Unlike a
 * null pick (automatic), it also silences the modes whose app and field
 * bindings match, until the pick is cleared like any other.
 */
const val NoKeyboardModeId = "__no_mode__"

/**
 * Picks the active mode: a manual pick from the Modes tool wins, then the
 * most specific automatic match. A mode that names hint texts beats one that
 * names field kinds, which beats one that only names apps — so a password
 * box inside a code editor still gets the password mode, and the "Extension"
 * box of a rename dialog gets the mode written for it rather than the one
 * for every single-line field. Ties break on the order of [modes].
 *
 * @param hint the focused field's placeholder text, null when it has none.
 */
fun resolveKeyboardMode(
    modes: List<KeyboardMode>,
    packageName: String?,
    fieldKinds: Set<ModeField>,
    manualModeId: String?,
    hint: String? = null,
): KeyboardMode? {
    if (manualModeId == NoKeyboardModeId) return null
    manualModeId?.let { id -> modes.firstOrNull { it.id == id }?.let { return it } }
    val matching = modes.filter { it.matchesField(packageName, fieldKinds, hint) }
    return matching.firstOrNull { it.hints.isNotEmpty() }
        ?: matching.firstOrNull { it.fieldKinds.isNotEmpty() }
        ?: matching.firstOrNull()
}

/**
 * The mode's pinned toolbar over [base] — replacing it, appending to it, or
 * inheriting it untouched. Appending keeps the user's own pins first and
 * drops duplicates so a tool never shows up twice.
 */
private fun KeyboardMode.pinnedOver(base: List<ToolbarTool>): List<ToolbarTool> {
    val tools = toolbarTools?.filter(::isSupportedTool) ?: return base
    return if (toolbarToolsAppend) base + (tools - base.toSet()) else tools
}

/** The settings as they apply while [mode] is active (null = no mode). */
fun KeyboardSettings.applyMode(mode: KeyboardMode?): KeyboardSettings {
    if (mode == null) return this
    val pinned = mode.pinnedOver(toolbarTools)
    // Only the pins the *mode* names. `pinned` falls back to the user's own
    // toolbar when the mode says nothing about tools, and force-enabling that
    // list would switch every tool they had turned off back on for as long as
    // any mode was active — a tool stays pinned when it is disabled, so Voice
    // came back in every chat app the moment the chat mode matched.
    val modePinned = mode.toolbarTools?.filter(::isSupportedTool).orEmpty()
    return copy(
        emojiBarMode = mode.emojiBarMode ?: emojiBarMode,
        keyboardThemeId = mode.themeId ?: keyboardThemeId,
        // A mode's theme has to beat the auto pair too, or "this mode looks
        // like this" would quietly mean "…unless auto-theme is on", which is
        // most of the time. Switched off only for the mode's own lifetime —
        // this is a view of the settings, not a write back to them.
        autoTheme = if (mode.themeId != null) autoTheme.copy(enabled = false) else autoTheme,
        toolbarTools = pinned,
        // A tool the mode pins has to be visible, even when the user left it
        // switched off globally — otherwise the mode silently pins nothing.
        enabledTools = enabledTools + (modePinned - enabledTools.toSet()),
        toolboxOrder = mode.toolboxOrder
            ?.let { front -> front + (toolboxOrder - front.toSet()) }
            ?: toolboxOrder,
        symbolRowEnabled = mode.symbolRowEnabled ?: symbolRowEnabled,
        symbolRowSetIds = mode.symbolSetIds ?: symbolRowSetIds,
        symbolRowActiveSetId = mode.symbolSetIds?.firstOrNull() ?: symbolRowActiveSetId,
        correction = mode.autocorrect
            ?.let { correction.copy(enabled = it) } ?: correction,
        // Several overrides land in the same group, so they are applied in turn
        // rather than each from the base — writing both as `?: autoText` would
        // let whichever came second drop the other's copy on the floor.
        autoText = autoText
            .let { t -> mode.autoCapitalize?.let { t.copy(capitalize = it) } ?: t }
            .let { t -> mode.autoSpace?.let { t.copy(spaceAfterPunctuation = it) } ?: t }
            .let { t -> mode.commitAsTyped?.let { t.copy(commitAsTyped = it) } ?: t },
        suggestions = mode.suggestions ?: suggestions,
        // The other spaces the keyboard types by itself, under the same
        // switch: the strip's, and both of the glide's. The space in front of a
        // glided word has no switch of its own — the global glide toggle names
        // only the trailing one — but "every space is one you typed" has to
        // mean it too, or two swiped words under an Off mode still came out
        // spaced (#184).
        suggestionStrip = mode.autoSpace
            ?.let { suggestionStrip.copy(autoSpaceAfterSuggestion = it) } ?: suggestionStrip,
        // Applied in turn with the two-thumb switch, as autoText's pair is.
        gesture = gesture
            .let { g -> mode.autoSpace?.let { g.copy(autoSpaceAfterGlide = it, autoSpaceBeforeGlide = it) } ?: g }
            .let { g -> mode.twoThumb?.let { g.copy(twoThumbGlide = it) } ?: g },
        gestureTyping = mode.glide ?: gestureTyping,
        // Only honoured while the layout is actually available. A mode naming a
        // layout the user has since switched off would otherwise pin the
        // keyboard to something that cannot be drawn.
        activeLayoutId = mode.layoutId?.takeIf { it in enabledLayoutIds } ?: activeLayoutId,
    )
}

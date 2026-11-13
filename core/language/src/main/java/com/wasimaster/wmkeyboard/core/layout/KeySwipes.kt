package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * Flick arms the user puts on keys from Settings, over whatever layout is on
 * screen: up-right on `q` types 1 on QWERTY, AZERTY and Colemak alike, without
 * forking each layout in the editor. The Unexpected Keyboard and CleverKeys
 * idea of eight swipes on every letter, for a user who wants it on a layout
 * that was not built that way.
 *
 * A key is named the way a `?123` chord names it, by the one character it
 * types, lowercased ([SymbolChord.tokenOf]), so the arms follow the letter
 * when a layout moves it.
 */
typealias KeySwipeArms = Map<FlickDirection, KeyAlternate>

/**
 * [this] key with the user's [arms] added, or [this] when none apply.
 *
 * The layout comes first: a direction the key already has an arm for keeps
 * it, so a MessagEase board or a Japanese pad is not rewritten by a preset
 * meant for QWERTY. A key whose hold runs an action or repeats is left alone,
 * because giving it arms would take that hold away (see [canHoldAction] and
 * [canRepeatOnHold]).
 *
 * A text arm becomes a [Key.flick] entry rather than an action that types, so
 * it draws, capitalises and reads back exactly like an arm the layout wrote.
 */
fun Key.withSwipeArms(arms: KeySwipeArms?): Key {
    if (arms.isNullOrEmpty() || action != KeyAction.Text || hasHoldAction() || holdIsSpokenFor()) return this
    var text = flick
    var actions = flickActions
    for (direction in FlickDirection.entries) {
        val arm = arms[direction] ?: continue
        if (flickArm(direction) != null) continue
        when (arm.action) {
            KeyAction.None, is KeyAction.Unknown -> Unit
            KeyAction.Text -> if (arm.label.isNotEmpty()) text = text + (direction to arm.label)
            else -> actions = actions + (direction to arm)
        }
    }
    return if (text === flick && actions === flickActions) this else copy(flick = text, flickActions = actions)
}

/** Ready-made sets of arms, merged into the user's own by [plusPreset]. */
enum class KeySwipePreset {
    /** The digits up-right on the top row, the way Unexpected Keyboard has them. */
    NUMBERS,

    /** The number row's shifted symbols up-left on the same keys. */
    SYMBOLS,

    /** Select all, copy, paste, cut, undo and redo down-left on `a c v x z y`. */
    EDITING,

    /**
     * Fast punctuation on the period key: a comma to the left, a question mark
     * up and an exclamation mark to the right, with the tap still a period
     * (FUTO's quick period key; competitor gap list item 7).
     */
    PUNCTUATION,
    ;

    val arms: Map<String, KeySwipeArms>
        get() = when (this) {
            NUMBERS -> topRow("1234567890".map(Char::toString), FlickDirection.UP_RIGHT)
            SYMBOLS -> topRow(listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"), FlickDirection.UP_LEFT)
            EDITING -> listOf(
                "a" to KeyAction.Edit(TextEditAction.SELECT_ALL),
                "c" to KeyAction.Edit(TextEditAction.COPY),
                "v" to KeyAction.Edit(TextEditAction.PASTE),
                "x" to KeyAction.Edit(TextEditAction.CUT),
                "z" to KeyAction.Tool(ToolbarTool.UNDO),
                "y" to KeyAction.Tool(ToolbarTool.REDO),
            ).associate { (token, action) -> token to mapOf(FlickDirection.DOWN_LEFT to KeyAlternate(action)) }
            PUNCTUATION -> mapOf(
                "." to mapOf(
                    FlickDirection.LEFT to KeyAlternate(KeyAction.Text, label = ","),
                    FlickDirection.UP to KeyAlternate(KeyAction.Text, label = "?"),
                    FlickDirection.RIGHT to KeyAlternate(KeyAction.Text, label = "!"),
                ),
            )
        }

    private fun topRow(texts: List<String>, direction: FlickDirection): Map<String, KeySwipeArms> =
        "qwertyuiop".map(Char::toString).zip(texts)
            .associate { (token, text) -> token to mapOf(direction to KeyAlternate(KeyAction.Text, label = text)) }
}

/**
 * These arms with [preset]'s merged in. A slot the preset fills replaces what
 * the user had there, since choosing a preset is asking for its arms; every
 * other slot stays.
 */
fun Map<String, KeySwipeArms>.plusPreset(preset: KeySwipePreset): Map<String, KeySwipeArms> {
    val merged = toMutableMap()
    for ((token, arms) in preset.arms) merged[token] = merged[token].orEmpty() + arms
    return merged
}

/**
 * These arms with [direction] on [token] set to [binding], or cleared when it
 * is null or [KeyAction.None]. A key left with no arms is dropped.
 */
fun Map<String, KeySwipeArms>.withKeySwipe(
    token: String,
    direction: FlickDirection,
    binding: KeyAlternate?,
): Map<String, KeySwipeArms> {
    val arms = this[token].orEmpty()
    val next = if (binding == null || binding.action == KeyAction.None) arms - direction else arms + (direction to binding)
    return if (next.isEmpty()) this - token else this + (token to next)
}

/**
 * The stored form of the arms: one JSON object keyed by the key's character,
 * each value an object keyed by the direction's layout-format name (`up_right`)
 * holding a [KeyAlternate], the way [SymbolChordCodec] stores a chord.
 *
 * A key or direction this build cannot name, or an arm it cannot decode, costs
 * that entry and no more. An action from a newer build decodes to
 * [KeyAction.Unknown] and is dropped, as is an arm bound to nothing.
 */
object KeySwipeCodec {
    private val directionNames: Map<FlickDirection, String> = FlickDirection.entries.associateWith {
        layoutJson.encodeToJsonElement(FlickDirection.serializer(), it).jsonPrimitive.content
    }
    private val directionsByName: Map<String, FlickDirection> =
        directionNames.entries.associate { (direction, name) -> name to direction }

    fun decode(json: String?): Map<String, KeySwipeArms> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = runCatching { layoutJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return root.entries.mapNotNull { (token, element) ->
            if (!SymbolChord.isToken(token)) return@mapNotNull null
            val arms = (element as? JsonObject)?.entries?.mapNotNull { (name, value) ->
                val direction = directionsByName[name] ?: return@mapNotNull null
                val binding = runCatching { layoutJson.decodeFromJsonElement<KeyAlternate>(value) }.getOrNull()
                    ?: return@mapNotNull null
                if (binding.action is KeyAction.Unknown || binding.action == KeyAction.None) null else direction to binding
            }?.toMap()
            arms?.takeIf { it.isNotEmpty() }?.let { token to it }
        }.toMap()
    }

    fun encode(map: Map<String, KeySwipeArms>): String =
        JsonObject(
            map.entries
                .filter { it.value.isNotEmpty() }
                .sortedBy { it.key }
                .associate { (token, arms) ->
                    token to JsonObject(
                        FlickDirection.entries
                            .mapNotNull { direction ->
                                arms[direction]?.let { directionNames.getValue(direction) to layoutJson.encodeToJsonElement(it) }
                            }
                            .toMap(),
                    )
                },
        ).toString()
}

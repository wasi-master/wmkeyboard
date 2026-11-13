package com.wasimaster.wmkeyboard.core.layout

/**
 * Which grid a layout that has no symbols page of its own borrows for `?123`.
 *
 * A layout that wrote its own symbols page keeps it whatever this says; the
 * choice only replaces the shipped page every other layout falls back to (see
 * [LayoutSpec.compile]). The second page, `=\<`, is the shipped one either way.
 *
 * Stored by name, so renaming an entry is a settings migration.
 */
enum class SymbolsPage {
    /** The shipped page: a row of digits over two rows of symbols. */
    STANDARD,

    /**
     * Digits as a phone keypad on the right, the common symbols in narrow
     * columns on the left: the arrangement SwiftKey ships, asked for again and
     * again in the Telegram group. Its `{&=` key goes on to the second page.
     */
    NUMBER_PAD,
}

/**
 * The [SymbolsPage.NUMBER_PAD] grid.
 *
 * Every key takes its hold from the shipped symbol pages, so a hold on 4 still
 * gives ¼ and ¾ and a hold on ( still gives [ { <. The currency key is `$` for
 * the same reason the shipped page's is: a borrowed page swaps it for the
 * language's or the region's currency when it is drawn, so a phone in Britain
 * shows £ where SwiftKey's British page has it.
 */
object NumberPadSymbols {

    /** The narrow symbol columns: two of them fit where one digit column does, nearly. */
    private const val NARROW = 2f / 3f

    val layer: LayerSpec by lazy { LayerSpec(rows = rows()) }

    private fun rows(): List<List<Key>> {
        val shipped = BuiltInLayouts.default.layer(LayoutLayer.SYMBOLS)?.rows.orEmpty() +
            BuiltInLayouts.default.layer(LayoutLayer.SYMBOLS_SHIFTED)?.rows.orEmpty()
        val holds: Map<String, List<String>> = shipped.flatten()
            .filter { it.longPress.isNotEmpty() }
            .groupBy { it.label }
            .mapValues { (_, keys) -> keys.first().longPress }

        fun key(label: String, width: Float = 1f) = Key(label, longPress = holds[label].orEmpty(), width = width)
        fun narrow(label: String) = key(label, NARROW)

        return listOf(
            listOf(
                key("#"), narrow("$"), narrow("&"), narrow("_"), narrow("-"),
                key("1"), key("2"), key("3"), key("?"),
            ),
            listOf(
                key("@"), narrow("("), narrow(")"), narrow("="), narrow("+"),
                key("4"), key("5"), key("6"), key("!"),
            ),
            listOf(
                Key("{&=", action = KeyAction.Symbols),
                narrow("'"), narrow(":"), narrow("%"), narrow("/"),
                key("7"), key("8"), key("9"),
                Key("⌫", action = KeyAction.Delete),
            ),
            listOf(
                Key("ABC", action = KeyAction.Letters),
                narrow("\""),
                Key(" ", action = KeyAction.Space, width = 2 * NARROW),
                narrow("*"),
                Key(",", role = KeyRole.Comma, longPress = holds[","].orEmpty()),
                key("0"),
                Key(".", role = KeyRole.Period, longPress = holds["."].orEmpty()),
                Key("⏎", action = KeyAction.Enter),
            ),
        )
    }
}

/**
 * Whether this layout's symbols page is one the [SymbolsPage] setting may
 * replace: it has none, or the one it has is the page it shipped with. QWERTY
 * carries the shared page as a layer of its own (it is the default every other
 * layout falls back to) and French ships the same page with € on it, so "has a
 * symbols layer" alone would leave the setting doing nothing on either. A page
 * the user wrote, or one a language's own layout file brings, is kept.
 */
fun LayoutSpec.borrowsSymbolsPage(): Boolean {
    val own = layer(LayoutLayer.SYMBOLS) ?: return true
    return own == BuiltInLayouts.byId(id)?.layer(LayoutLayer.SYMBOLS)
}

/**
 * Whether [rows] carries all ten digits somewhere other than a leading digit
 * row: a number pad inside the page, as [NumberPadSymbols] has. The number row
 * over such a page would be a second copy of its own keys, and a 1234 key on it
 * would open a pad the page already is.
 *
 * A page that leads with its digit row says no here; that row is what the
 * number row stands in for, and the fill row takes its place instead (see
 * [leadsWithDigitRow]).
 */
fun carriesDigitPad(rows: List<List<Key>>): Boolean {
    if (leadsWithDigitRow(rows)) return false
    val digits = rows.asSequence().flatten()
        .filter { it.action == KeyAction.Text }
        .mapNotNull { key -> (key.output ?: key.label).singleOrNull()?.takeIf { it in '0'..'9' } }
        .toSet()
    return digits.size == 10
}

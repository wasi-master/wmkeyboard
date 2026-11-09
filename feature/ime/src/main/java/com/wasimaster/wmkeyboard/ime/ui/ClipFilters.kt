package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.Immutable
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem

/** Where one chip of the clipboard's filter row stands (#1187). */
internal enum class ClipChipState {
    OFF,

    /** Only the clips it names. */
    ON,

    /** Only the clips it does not name: the chip reads "Not …" (#1093, #1187). */
    NOT,
}

/**
 * Everything the clipboard's filter row has set (#612, #1093, #1187), and the
 * one place that says which clips it lets through.
 *
 * A tap on a chip that is off sets it alone in its row, which is the panel as
 * it was before chips combined. A press and hold adds it to what is already
 * set. A tap on a chip that is on turns it into its "Not" form, and another
 * lets it go.
 *
 * The rules, for a clip to show:
 * - [scope]: [ClipFilter.ALL], [ClipFilter.PINNED] or [ClipFilter.UNPINNED].
 *   Only ever one; holding the other swaps it.
 * - [kinds]: Text, Links, Images, Files. A clip is one kind, so with two of
 *   them set it is either: Text and Links is every text clip and every link.
 * - [notKinds]: none of these, so Pinned and Not links is the pinned clips
 *   that are not links.
 * - [ClipFilter.DUPLICATES] in [kinds] keeps the clips that share their text
 *   with another of the ones left; in [notKinds], the ones that don't.
 * - [tags]: any of them (#bug and #critical is the clips tagged either).
 * - [notTags]: none of them.
 *
 * Each rule must hold, so the scope, the kinds and the tags narrow each other.
 */
@Immutable
internal data class ClipFilters(
    val scope: ClipFilter = ClipFilter.ALL,
    val kinds: Set<ClipFilter> = emptySet(),
    val notKinds: Set<ClipFilter> = emptySet(),
    /** In the order picked, each once whatever its case. */
    val tags: List<String> = emptyList(),
    val notTags: List<String> = emptyList(),
) {
    /** Nothing is set: the whole history. */
    val isEmpty: Boolean
        get() = scope == ClipFilter.ALL && kinds.isEmpty() && notKinds.isEmpty() && tags.isEmpty() && notTags.isEmpty()

    /** How many chips are set, for the Clear chip. */
    val count: Int
        get() = (if (scope == ClipFilter.ALL) 0 else 1) + kinds.size + notKinds.size + tags.size + notTags.size

    /** Something in the kind row (the scope or a kind) is set. */
    val hasKind: Boolean get() = scope != ClipFilter.ALL || kinds.isNotEmpty() || notKinds.isNotEmpty()

    /** Some tag is set, either way. */
    val hasTag: Boolean get() = tags.isNotEmpty() || notTags.isNotEmpty()

    /** The duplicates filter is on, so the clips are drawn in their groups and a hold offers Keep only this one. */
    val duplicates: Boolean get() = ClipFilter.DUPLICATES in kinds

    /** A single tag and nothing against one: what opening a pinned folder sets (#768). */
    val singleTag: String? get() = tags.singleOrNull()?.takeIf { notTags.isEmpty() }

    fun stateOf(filter: ClipFilter): ClipChipState = when {
        filter == ClipFilter.ALL -> if (scope == ClipFilter.ALL && kinds.isEmpty() && notKinds.isEmpty()) ClipChipState.ON else ClipChipState.OFF
        filter.isScope -> if (scope == filter) ClipChipState.ON else ClipChipState.OFF
        filter in kinds -> ClipChipState.ON
        filter in notKinds -> ClipChipState.NOT
        else -> ClipChipState.OFF
    }

    fun stateOfTag(tag: String): ClipChipState = when {
        tags.any { it.equals(tag, ignoreCase = true) } -> ClipChipState.ON
        notTags.any { it.equals(tag, ignoreCase = true) } -> ClipChipState.NOT
        else -> ClipChipState.OFF
    }

    /**
     * A tap on [filter]'s chip. All clears the kind row. A chip that is off is
     * set alone in the kind row; one that is on turns to Not, and a Not lets
     * go. Pinned and Unpinned have no Not form (Not pinned is Unpinned), so a
     * second tap lets them go. The tags are left as they are.
     */
    fun tapped(filter: ClipFilter): ClipFilters = when {
        filter == ClipFilter.ALL -> copy(scope = ClipFilter.ALL, kinds = emptySet(), notKinds = emptySet())
        stateOf(filter) == ClipChipState.OFF ->
            if (filter.isScope) {
                copy(scope = filter, kinds = emptySet(), notKinds = emptySet())
            } else {
                copy(scope = ClipFilter.ALL, kinds = setOf(filter), notKinds = emptySet())
            }
        else -> cycled(filter)
    }

    /**
     * A press and hold on [filter]'s chip: added to what is set rather than
     * in its place. Pinned and Unpinned never combine with each other, so
     * holding one swaps it for the other, and holding All lets the scope go
     * while keeping the kinds. On a chip already set, a hold does what a tap
     * does.
     */
    fun held(filter: ClipFilter): ClipFilters = when {
        filter == ClipFilter.ALL -> copy(scope = ClipFilter.ALL)
        stateOf(filter) == ClipChipState.OFF ->
            if (filter.isScope) copy(scope = filter) else copy(kinds = kinds + filter, notKinds = notKinds - filter)
        else -> cycled(filter)
    }

    /** On to Not to off, the others in place. */
    private fun cycled(filter: ClipFilter): ClipFilters = when {
        filter.isScope -> copy(scope = ClipFilter.ALL)
        filter in kinds -> copy(kinds = kinds - filter, notKinds = notKinds + filter)
        else -> copy(notKinds = notKinds - filter)
    }

    /** A tap on [tag]'s chip: alone among the tags when it was off, else on to Not to off. */
    fun tappedTag(tag: String): ClipFilters = when (stateOfTag(tag)) {
        ClipChipState.OFF -> copy(tags = listOf(tag), notTags = emptyList())
        else -> cycledTag(tag)
    }

    /** A press and hold on [tag]'s chip: added to the tags already set. */
    fun heldTag(tag: String): ClipFilters = when (stateOfTag(tag)) {
        ClipChipState.OFF -> copy(tags = tags + tag)
        else -> cycledTag(tag)
    }

    private fun cycledTag(tag: String): ClipFilters = when (stateOfTag(tag)) {
        ClipChipState.ON -> copy(tags = tags.without(tag), notTags = notTags + tag)
        ClipChipState.NOT -> copy(notTags = notTags.without(tag))
        ClipChipState.OFF -> this
    }

    /** Only [tag], set the way a pinned folder opens (#768); the kind row is kept. */
    fun withOnlyTag(tag: String?): ClipFilters =
        copy(tags = listOfNotNull(tag), notTags = emptyList())

    /** The kind row as it is, without any tag: what the panel keeps for next time (#1084). */
    fun withoutTags(): ClipFilters = copy(tags = emptyList(), notTags = emptyList())

    /**
     * Without the tags no clip or note carries any more: a tag that went with
     * its last clip filters nothing in, and its chip is not there to undo it.
     */
    fun keepingTags(available: List<String>): ClipFilters {
        if (!hasTag) return this
        val keep = { tag: String -> available.any { it.equals(tag, ignoreCase = true) } }
        val kept = tags.filter(keep)
        val keptNot = notTags.filter(keep)
        return if (kept.size == tags.size && keptNot.size == notTags.size) this else copy(tags = kept, notTags = keptNot)
    }

    /** [item] passes every rule but the duplicates one, which needs the whole list; see [apply]. */
    fun admits(item: ClipItem): Boolean {
        if (!scope.matches(item)) return false
        val wanted = kinds.filter { it.isKind }
        if (wanted.isNotEmpty() && wanted.none { it.matches(item) }) return false
        if (notKinds.any { it.isKind && it.matches(item) }) return false
        if (tags.isNotEmpty() && tags.none { item.carries(it) }) return false
        if (notTags.any { item.carries(it) }) return false
        return true
    }

    /** The clips of [items] the row lets through, in their order; duplicates in their groups. */
    fun apply(items: List<ClipItem>): List<ClipItem> {
        if (isEmpty) return items
        val admitted = items.filter(::admits)
        return when {
            ClipFilter.DUPLICATES in kinds -> duplicateGroups(admitted)
            ClipFilter.DUPLICATES in notKinds -> {
                val grouped = duplicateGroups(admitted).mapTo(HashSet()) { it.id }
                admitted.filterNot { it.id in grouped }
            }
            else -> admitted
        }
    }

    /**
     * The search lists the notes' passages too (#1104): a note is text, never a
     * pin, a link or a picture, and never a copy of a clip. So no scope, no
     * duplicates, and among the kinds only Text, and Text not left out.
     */
    val admitsNotes: Boolean
        get() = scope == ClipFilter.ALL &&
            ClipFilter.DUPLICATES !in kinds &&
            (kinds.isEmpty() || ClipFilter.TEXT in kinds) &&
            ClipFilter.TEXT !in notKinds
}

/** All, Pinned and Unpinned, which only one of is set at a time (#1187). */
internal val ClipFilter.isScope: Boolean
    get() = this == ClipFilter.ALL || this == ClipFilter.PINNED || this == ClipFilter.UNPINNED

/** Text, Links, Images and Files: what a clip is, one of them each. */
internal val ClipFilter.isKind: Boolean
    get() = !isScope && this != ClipFilter.DUPLICATES

private fun ClipItem.carries(tag: String): Boolean = tags.any { it.equals(tag, ignoreCase = true) }

private fun List<String>.without(tag: String): List<String> = filterNot { it.equals(tag, ignoreCase = true) }

package com.wasimaster.wmkeyboard.core.tools

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.tools.R

/**
 * One browsable category in the GIF/sticker default view. Pressing it runs
 * [term] as a search.
 *
 * The words on the chip and the words sent to the provider are two separate
 * values. A category that came from a provider arrives with its own [label],
 * already in whatever language that API answered in; a bundled one carries a
 * [labelRes] that gets translated with the rest of the app, while its term
 * stays the English the providers index on. [PhotoTopic] splits the same pair
 * for the same reason.
 */
data class MediaCategory(
    /** Sent to the provider. Never translated. */
    val term: String,
    /** The provider's own words. Blank for a bundled category. */
    val label: String = "",
    /** Our wording for a bundled category; 0 when [label] carries the words. */
    @StringRes val labelRes: Int = 0,
)

/**
 * The category row's catalogue: what to show when a provider has no
 * categories of its own, and the tidy-up every provider list goes through.
 *
 * The bundled list is the floor. A provider that answers with nothing, that
 * has no such endpoint, or that cannot be reached at all still leaves the
 * user a row to browse, offline included.
 */
object MediaCategories {

    /** More than a couple of scrolls of chips is nobody's idea of browsing. */
    const val MAX_CATEGORIES = 24

    /** A provider tag longer than this is a sentence, not a category. */
    private const val MAX_TERM_LENGTH = 32

    /**
     * The GIF panel's favourites chip (#597). It rides the category row and
     * its selection, but it is no search: the panel shows its own list
     * ([GifCollections]). The NUL keeps it apart from any term a provider
     * could send.
     */
    const val FAVOURITES = "\u0000favourites"

    /** The recent GIFs chip, the same kind of entry as [FAVOURITES]. */
    const val RECENT = "\u0000recent"

    /** Whether [term] is one of the panel's own lists rather than a search. */
    fun isCollection(term: String?): Boolean = term == FAVOURITES || term == RECENT

    /**
     * KLIPY's memes chip on the GIF panel: a tab rather than a search, so the
     * search box searches the memes while it is selected. Shown only when a
     * KLIPY row is on the GIF list.
     */
    const val MEMES = "\u0000memes"

    /** The memes chip, for the start of the category row. */
    val memes: MediaCategory = MediaCategory(term = MEMES, labelRes = R.string.core_tools_media_category_memes)

    /** Whether [term] is a chip of the panel's own rather than a word to search for. */
    fun isOwnChip(term: String?): Boolean = isCollection(term) || term == MEMES

    val bundledGif: List<MediaCategory> = listOf(
        bundled("reactions", R.string.core_tools_media_category_reactions),
        bundled("love", R.string.core_tools_media_category_love),
        bundled("happy", R.string.core_tools_media_category_happy),
        bundled("sad", R.string.core_tools_media_category_sad),
        bundled("angry", R.string.core_tools_media_category_angry),
        bundled("laugh", R.string.core_tools_media_category_laugh),
        bundled("dance", R.string.core_tools_media_category_dance),
        bundled("thanks", R.string.core_tools_media_category_thanks),
        bundled("hello", R.string.core_tools_media_category_hello),
        bundled("yes", R.string.core_tools_media_category_yes),
        bundled("no", R.string.core_tools_media_category_no),
        bundled("wow", R.string.core_tools_media_category_wow),
        bundled("sports", R.string.core_tools_media_category_sports),
        bundled("animals", R.string.core_tools_media_category_animals),
        bundled("food", R.string.core_tools_media_category_food),
        bundled("sleep", R.string.core_tools_media_category_sleep),
    )

    val bundledSticker: List<MediaCategory> = listOf(
        bundled("love", R.string.core_tools_media_category_love),
        bundled("happy", R.string.core_tools_media_category_happy),
        bundled("sad", R.string.core_tools_media_category_sad),
        bundled("cute", R.string.core_tools_media_category_cute),
        bundled("funny", R.string.core_tools_media_category_funny),
        bundled("thanks", R.string.core_tools_media_category_thanks),
        bundled("hello", R.string.core_tools_media_category_hello),
        bundled("yes", R.string.core_tools_media_category_yes),
        bundled("no", R.string.core_tools_media_category_no),
        bundled("party", R.string.core_tools_media_category_party),
        bundled("animals", R.string.core_tools_media_category_animals),
        bundled("food", R.string.core_tools_media_category_food),
        bundled("work", R.string.core_tools_media_category_work),
        bundled("sleep", R.string.core_tools_media_category_sleep),
    )

    fun bundled(sticker: Boolean): List<MediaCategory> =
        if (sticker) bundledSticker else bundledGif

    /**
     * Cleans up what a provider sent and falls back to [bundled] when there
     * is nothing usable left. Every way the network side can disappoint —
     * an endpoint that does not exist, a shape we do not know, an empty
     * list — arrives here as an empty list and leaves with a row to show.
     */
    fun normalise(raw: List<MediaCategory>, sticker: Boolean): List<MediaCategory> {
        val seen = HashSet<String>()
        val clean = ArrayList<MediaCategory>(raw.size)
        for (category in raw) {
            val term = category.term.trim()
            if (term.isEmpty() || term.length > MAX_TERM_LENGTH) continue
            if (!seen.add(term.lowercase())) continue
            clean += category.copy(term = term, label = category.label.trim())
            if (clean.size == MAX_CATEGORIES) break
        }
        return clean.ifEmpty { bundled(sticker) }
    }

    private fun bundled(term: String, @StringRes labelRes: Int) =
        MediaCategory(term = term, labelRes = labelRes)
}

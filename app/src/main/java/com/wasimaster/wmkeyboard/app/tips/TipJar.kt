package com.wasimaster.wmkeyboard.app.tips

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.R
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bolt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cake
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fastfood
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MilitaryTech
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mood
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Star
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tips through Google Play billing, as the Supporters screen sees them.
 *
 * The Play build may not link to SupportKori: Play's Payments policy lets an
 * app take money only through Play's own billing, tax-exempt charities aside.
 * So that build sells the tiers below as consumable one-time products, and
 * every other build keeps the SupportKori link and never links Play Billing
 * at all, which F-Droid would refuse.
 *
 * Same seam as the updater: the interface is here so the screen compiles
 * everywhere, `src/play/java` drives Google's BillingClient, and
 * `src/noplay/java` returns [NoTipJar]. Exactly one of the two is compiled.
 */
internal interface TipJar {

    /** What Play is selling, once it has said. */
    val offers: StateFlow<TipOffers>

    /** Where the tip the user last pressed has got to. */
    val purchase: StateFlow<TipPurchase>

    /**
     * Opens Play's purchase sheet for [tier]. [name] is what the Supporters
     * list should show once Play says the order is paid; blank stays off it.
     * [message] goes with the name onto the website's list (the app's list
     * shows names only) and is dropped with it.
     */
    fun buy(tier: TipTier, name: String, message: String)

    /** Back to [TipPurchase.Idle], after the thank-you has been seen. */
    fun reset()
}

/**
 * The products to create in Play Console › Monetize › In-app products, all
 * consumable so the same tier can be bought again. The id is what the code
 * and the server match on; the price is whatever Console says, localised by
 * Play, and [usd] is only the middle band's price. High- and low-income
 * countries are priced on their own ladders in Console (see play-tip.ts).
 */
internal enum class TipTier(
    val productId: String,
    val usd: String,
    @StringRes val label: Int,
    val icon: ImageVector,
    /** The tile's two-stop wash, warmer as the tier grows. Theme-independent, like confetti. */
    val wash: Pair<Color, Color>,
) {
    THANKS("tip_1", "0.99", R.string.tips_tier_thanks, Icons.Outlined.Mood, Color(0xFF7FB8FF) to Color(0xFF8C9EFF)),
    BOOST("tip_2", "1.99", R.string.tips_tier_boost, Icons.Outlined.Bolt, Color(0xFF64D8CB) to Color(0xFF4FC3F7)),
    LUNCH("tip_5", "4.99", R.string.tips_tier_lunch, Icons.Outlined.Fastfood, Color(0xFF9CCC65) to Color(0xFF26A69A)),
    TREAT("tip_10", "9.99", R.string.tips_tier_treat, Icons.Outlined.Cake, Color(0xFFFFCA28) to Color(0xFFFF8A65)),
    STAR("tip_20", "19.99", R.string.tips_tier_star, Icons.Outlined.Star, Color(0xFFFFB74D) to Color(0xFFF06292)),
    CHAMPION("tip_50", "49.99", R.string.tips_tier_champion, Icons.Outlined.MilitaryTech, Color(0xFFF06292) to Color(0xFFAB47BC)),
    LEGEND("tip_100", "99.99", R.string.tips_tier_legend, Icons.Outlined.AutoAwesome, Color(0xFFBA68C8) to Color(0xFF5C6BC0)),
    ;

    companion object {
        /** The tier a screen opens on: a middle one, so neither end reads as the expected answer. */
        val DEFAULT = LUNCH

        fun of(productId: String): TipTier? = entries.firstOrNull { it.productId == productId }
    }
}

/** One tier as Play is selling it, with Play's own formatted, localised price. */
internal data class TipOffer(val tier: TipTier, val price: String)

internal sealed interface TipOffers {
    /** Still asking Play. */
    data object Loading : TipOffers

    /** No Play Store, no billing on this device, or no products set up yet. */
    data object Unavailable : TipOffers

    data class Ready(val offers: List<TipOffer>) : TipOffers
}

internal sealed interface TipPurchase {
    data object Idle : TipPurchase

    /** Play's sheet is open. */
    data class Paying(val tier: TipTier) : TipPurchase

    /**
     * Paid with a method that settles later (cash at a shop, some carrier
     * billing). Play finishes it on its own; the next visit picks it up.
     */
    data class Pending(val tier: TipTier) : TipPurchase

    /** Paid and done. [name] is null when the user chose not to be listed. */
    data class Thanked(val tier: TipTier, val name: String?) : TipPurchase

    data object Failed : TipPurchase
}

/** The jar for every build that has no Play billing behind it. */
internal object NoTipJar : TipJar {
    override val offers: StateFlow<TipOffers> = MutableStateFlow(TipOffers.Unavailable)
    override val purchase: StateFlow<TipPurchase> = MutableStateFlow(TipPurchase.Idle)
    override fun buy(tier: TipTier, name: String, message: String) = Unit
    override fun reset() = Unit
}

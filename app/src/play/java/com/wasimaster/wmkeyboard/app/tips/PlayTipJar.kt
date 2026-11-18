package com.wasimaster.wmkeyboard.app.tips

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Binds a Play [BillingClient] to the Supporters screen for as long as it is
 * on screen. The Play-channel half of the pair; `src/noplay/java` declares the
 * same function and returns [NoTipJar].
 *
 * Scoped to the screen rather than the activity: nobody else sells anything,
 * and a connection to Play held open from every settings page would be one
 * more thing running for no reason.
 */
@Composable
internal fun rememberTipJar(): TipJar {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() } ?: return NoTipJar
    val jar = remember(activity) { PlayTipJar(activity) }
    DisposableEffect(jar) {
        jar.connect()
        onDispose { jar.close() }
    }
    return jar
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The Play Billing driver.
 *
 * A tip is a consumable product: it is consumed the moment Play reports it
 * paid, which is also what acknowledges it (an order left unacknowledged for
 * three days is refunded by Play), and what lets the same tier be bought again.
 * Nothing is unlocked by it, by design. Play's policy is fine with a tip that
 * buys nothing, and the app has nothing to sell.
 *
 * Every connection first asks Play for orders it has not seen finished. That
 * covers a process killed between payment and consumption, and a pending
 * payment that settled while the app was closed, so no tip is ever left to be
 * refunded just because the screen was not open at the right moment.
 */
private class PlayTipJar(private val activity: Activity) : TipJar {

    private val credits = TipCredits(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _offers = MutableStateFlow<TipOffers>(TipOffers.Loading)
    override val offers: StateFlow<TipOffers> = _offers.asStateFlow()

    private val _purchase = MutableStateFlow<TipPurchase>(TipPurchase.Idle)
    override val purchase: StateFlow<TipPurchase> = _purchase.asStateFlow()

    /** Play's details per tier, kept to build the purchase sheet from. */
    private var details: Map<TipTier, ProductDetails> = emptyMap()

    /** Tokens already being consumed, so a purchase reported twice is consumed once. */
    private val consuming = mutableSetOf<String>()

    private val client: BillingClient = BillingClient.newBuilder(activity)
        .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingResponseCode.OK) {
                    DebugLog.d(TAG, "billing unavailable: ${result.responseCode} ${result.debugMessage}")
                    _offers.value = TipOffers.Unavailable
                    return
                }
                queryOffers()
                recoverPurchases()
            }

            // enableAutoServiceReconnection() reconnects on the next call by
            // itself, so a drop needs no handling of its own here.
            override fun onBillingServiceDisconnected() = Unit
        })
        // Credits that could not be sent last time, such as before the
        // server was set up, go out again whenever the screen opens.
        scope.launch(Dispatchers.IO) { credits.flush() }
    }

    fun close() {
        scope.cancel()
        client.endConnection()
    }

    override fun buy(tier: TipTier, name: String, message: String) {
        val product = details[tier] ?: return
        val offer = product.oneTimePurchaseOfferDetailsList?.firstOrNull()
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product)
            .apply { offer?.offerToken?.let { setOfferToken(it) } }
            .build()
        credits.intend(tier, name, message)
        _purchase.value = TipPurchase.Paying(tier)
        val result = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build(),
        )
        if (result.responseCode != BillingResponseCode.OK) {
            DebugLog.w(TAG, "could not open the purchase sheet: ${result.responseCode} ${result.debugMessage}")
            _purchase.value = TipPurchase.Failed
        }
    }

    override fun reset() {
        _purchase.value = TipPurchase.Idle
    }

    private fun queryOffers() {
        val products = TipTier.entries.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it.productId)
                .setProductType(ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        client.queryProductDetailsAsync(params) { result, found ->
            if (result.responseCode != BillingResponseCode.OK) {
                DebugLog.w(TAG, "product query failed: ${result.responseCode} ${result.debugMessage}")
                _offers.value = TipOffers.Unavailable
                return@queryProductDetailsAsync
            }
            val byTier = found.productDetailsList.mapNotNull { product ->
                TipTier.of(product.productId)?.let { it to product }
            }.toMap()
            details = byTier
            // In tier order, not Play's: the row reads from small to large.
            val offers = TipTier.entries.mapNotNull { tier ->
                val product = byTier[tier] ?: return@mapNotNull null
                val price = product.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
                    ?: return@mapNotNull null
                TipOffer(tier, price)
            }
            // A Play Console with none of the products yet reads as no jar,
            // rather than an empty row of tiles.
            _offers.value = if (offers.isEmpty()) TipOffers.Unavailable else TipOffers.Ready(offers)
        }
    }

    private fun recoverPurchases() {
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingResponseCode.OK) return@queryPurchasesAsync
            // Recovered quietly: the thank-you belongs to a press on this
            // screen, not to an order from last week.
            purchases.forEach { handle(it, celebrate = false) }
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> purchases.orEmpty().forEach { handle(it, celebrate = true) }
            BillingResponseCode.USER_CANCELED -> _purchase.value = TipPurchase.Idle
            // An earlier order of the same tier was never consumed. Finishing
            // it is the fix, and then the tier can be bought again.
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _purchase.value = TipPurchase.Idle
                recoverPurchases()
            }
            else -> {
                DebugLog.w(TAG, "purchase failed: ${result.responseCode} ${result.debugMessage}")
                _purchase.value = TipPurchase.Failed
            }
        }
    }

    private fun handle(purchase: Purchase, celebrate: Boolean) {
        val tier = purchase.products.firstNotNullOfOrNull(TipTier::of) ?: return
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> if (celebrate) _purchase.value = TipPurchase.Pending(tier)
            Purchase.PurchaseState.PURCHASED -> consume(purchase, tier, celebrate)
            else -> Unit
        }
    }

    private fun consume(purchase: Purchase, tier: TipTier, celebrate: Boolean) {
        val token = purchase.purchaseToken
        if (!consuming.add(token)) return
        val params = ConsumeParams.newBuilder().setPurchaseToken(token).build()
        client.consumeAsync(params) { result, _ ->
            consuming.remove(token)
            if (result.responseCode != BillingResponseCode.OK) {
                // Left for the next connection's recoverPurchases to finish.
                DebugLog.w(TAG, "consume failed: ${result.responseCode} ${result.debugMessage}")
                if (celebrate) _purchase.value = TipPurchase.Failed
                return@consumeAsync
            }
            val name = credits.intendedName(tier)
            credits.enqueue(tier, token, name, credits.intendedMessage(tier))
            if (celebrate) _purchase.value = TipPurchase.Thanked(tier, name.ifBlank { null })
            scope.launch(Dispatchers.IO) { credits.flush() }
        }
    }
}

private const val TAG = "Tips"

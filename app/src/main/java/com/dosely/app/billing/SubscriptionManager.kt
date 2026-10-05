package com.dosely.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SubscriptionState(
    val adFree: Boolean? = null,
    val price: String? = null,
    val available: Boolean = false,
    val message: String = "Checking Google Play…",
)

/** Play is the source of truth; there is no user-editable premium preference. */
class SubscriptionManager private constructor(context: Context) : PurchasesUpdatedListener {
    private val cache = context.applicationContext.getSharedPreferences("play_entitlement", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(SubscriptionState(adFree = cache.getBoolean("ad_free", false)))
    val state = mutable.asStateFlow()
    private var product: ProductDetails? = null
    private var connecting = false
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    @Synchronized fun refresh() {
        if (client.isReady) { query(); return }
        if (connecting) return
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) query()
                else mutable.value = mutable.value.copy(message = "Google Play is unavailable. Tap Restore to retry.", available = false)
            }
            override fun onBillingServiceDisconnected() { connecting = false }
        })
    }

    private fun query() {
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.SUBS).build(),
        )).build()) { result, response ->
            product = response.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
            val offer = monthlyOffer()
            mutable.value = mutable.value.copy(
                price = offer?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice,
                available = result.responseCode == BillingClient.BillingResponseCode.OK && offer != null,
                message = if (offer == null) "Subscription is not available in Google Play yet." else "Monthly subscription. Auto-renews; cancel in Google Play.",
            )
        }
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                mutable.value = mutable.value.copy(message = "Could not check purchases. Tap Restore to retry.")
                return@queryPurchasesAsync
            }
            val active = purchases.filter { PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            mutable.value = mutable.value.copy(adFree = active.isNotEmpty())
            cache.edit().putBoolean("ad_free", active.isNotEmpty()).apply()
            active.filterNot { it.isAcknowledged }.forEach { purchase ->
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { ack ->
                    if (ack.responseCode != BillingClient.BillingResponseCode.OK) {
                        mutable.value = mutable.value.copy(message = "Purchase received. Reopen the app online to finish confirmation.")
                    }
                }
            }
            if (purchases.any { PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PENDING }) {
                mutable.value = mutable.value.copy(message = "Payment pending. Ads are removed after Google Play confirms payment.")
            }
        }
    }

    private fun monthlyOffer() = product?.subscriptionOfferDetails?.firstOrNull {
        it.basePlanId == "monthly" && it.offerId == null &&
            it.pricingPhases.pricingPhaseList.lastOrNull()?.billingPeriod == "P1M"
    }

    fun purchase(activity: Activity) {
        val details = product
        val offer = monthlyOffer()
        if (details == null || offer == null) {
            if (com.dosely.app.BuildConfig.DEBUG) {
                toggleDebugAdFree()
                return
            }
            return refresh()
        }
        val result = client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).setOfferToken(offer.offerToken).build(),
        )).build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            mutable.value = mutable.value.copy(message = "Could not open Google Play checkout. Please try again.")
        }
    }

    fun toggleDebugAdFree() {
        val next = mutable.value.adFree != true
        mutable.value = mutable.value.copy(
            adFree = next,
            message = if (next) "Ad-free test mode active ($0.99/mo). Ads disabled." else "Ad-free disabled. Ads enabled.",
        )
        cache.edit().putBoolean("ad_free", next).apply()
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK, BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            BillingClient.BillingResponseCode.USER_CANCELED -> mutable.value = mutable.value.copy(message = "Purchase canceled. You have not been charged.")
            else -> mutable.value = mutable.value.copy(message = "Purchase could not be completed. Please try again.")
        }
    }

    companion object {
        const val PRODUCT_ID = "dosely_ad_free"
        @Volatile private var instance: SubscriptionManager? = null
        fun get(context: Context): SubscriptionManager = instance ?: synchronized(this) {
            instance ?: SubscriptionManager(context).also { instance = it }
        }
    }
}

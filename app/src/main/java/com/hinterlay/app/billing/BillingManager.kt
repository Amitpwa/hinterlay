package com.hinterlay.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.hinterlay.app.data.AdFree
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Play Billing for voluntary support.
 *
 * Create these in Play Console -> Monetize -> In-app products (all "Active"):
 *   tip_small, tip_medium, tip_large : consumable, any price (e.g. $1 / $5 / $20), repeatable
 *   lifetime_ad_free                 : one-time, set the price to $100 - removes ads forever
 */
object BillingManager {
    const val TIP_SMALL = "tip_small"
    const val TIP_MEDIUM = "tip_medium"
    const val TIP_LARGE = "tip_large"
    const val LIFETIME = "lifetime_ad_free"
    private val TIPS = listOf(TIP_SMALL, TIP_MEDIUM, TIP_LARGE)

    private lateinit var client: BillingClient
    private var started = false

    private val _products = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val products: StateFlow<Map<String, ProductDetails>> = _products.asStateFlow()

    /** One-shot message for the UI ("Thank you!", errors). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun messageShown() { _message.value = null }

    fun start(context: Context) {
        if (started) return
        started = true
        client = BillingClient.newBuilder(context.applicationContext)
            .setListener { result, purchases ->
                when {
                    result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null ->
                        purchases.forEach(::handle)
                    result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED -> Unit
                    else -> _message.value = "Purchase didn't go through. You weren't charged."
                }
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        connect()
    }

    private fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    loadProducts()
                    restore()
                }
            }

            override fun onBillingServiceDisconnected() = Unit // reconnected lazily on next use
        })
    }

    private fun loadProducts() {
        val list = (TIPS + LIFETIME).map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(list).build()) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _products.value = details.productDetailsList.associateBy { it.productId }
            }
        }
    }

    /** Re-applies the lifetime unlock after a reinstall or on a new device. */
    private fun restore() {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases.forEach(::handle)
        }
    }

    fun buy(activity: Activity, productId: String) {
        if (!client.isReady) {
            _message.value = "Google Play isn't available right now. Try again in a moment."
            connect()
            return
        }
        val details = _products.value[productId] ?: run {
            _message.value = "This option isn't available yet."
            return
        }
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build())
        ).build()
        client.launchBillingFlow(activity, params)
    }

    private fun handle(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val id = purchase.products.firstOrNull() ?: return
        when {
            id == LIFETIME -> {
                AdFree.setLifetime(true)
                com.hinterlay.app.cloud.Track.event("purchase_completed", "product" to id)
                if (!purchase.isAcknowledged) {
                    client.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                    ) {}
                }
                _message.value = "You're a lifetime supporter - no more ads, ever. Thank you!"
            }
            id in TIPS -> client.consumeAsync(
                ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            ) { r, _ ->
                if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    com.hinterlay.app.cloud.Track.event("purchase_completed", "product" to id)
                    _message.value = "Thank you for supporting Hinterlay!"
                }
            }
        }
    }
}

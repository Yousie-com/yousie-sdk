package com.yousie.sdk.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The app's own subscriptions, read through the Play Billing Library the
 * app already ships. The SDK brings none: it compiles against the library
 * and uses it only when [createOrNull] finds a recent enough one.
 *
 * Each read opens its own short connection and closes it. It never starts a
 * purchase, never acknowledges or consumes one, and its purchase listener
 * does nothing, so the app's own billing code is left alone.
 */
internal class PlayPurchaseSource private constructor(
    private val context: Context,
    private val log: (String) -> Unit,
) : PurchaseSource {

    override fun subscriptions(): List<StorePurchase>? {
        val done = CountDownLatch(1)
        val found = AtomicReference<List<StorePurchase>?>(null)
        val opened = AtomicReference<BillingClient?>(null)
        val main = Handler(Looper.getMainLooper())
        // Play Billing is used from the main thread, where its callbacks
        // arrive too; the worker only waits.
        main.post {
            try {
                val client = BillingClient.newBuilder(context)
                    .setListener { _, _ -> }
                    .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                    .build()
                opened.set(client)
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        try {
                            if (result.responseCode != OK) {
                                done.countDown()
                                return
                            }
                            val params = QueryPurchasesParams.newBuilder().setProductType(SUBS).build()
                            client.queryPurchasesAsync(params) { answer, purchases ->
                                try {
                                    if (answer.responseCode == OK) {
                                        found.compareAndSet(null, purchases.mapNotNull(::convert))
                                    }
                                } catch (t: Throwable) {
                                    log("purchases: ${t.javaClass.simpleName}")
                                } finally {
                                    done.countDown()
                                }
                            }
                        } catch (t: Throwable) {
                            log("purchases: ${t.javaClass.simpleName}")
                            done.countDown()
                        }
                    }

                    override fun onBillingServiceDisconnected() {
                        done.countDown()
                    }
                })
            } catch (t: Throwable) {
                log("purchases: ${t.javaClass.simpleName}")
                done.countDown()
            }
        }
        try {
            done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        main.post {
            try {
                opened.get()?.endConnection()
            } catch (t: Throwable) {
                // Already gone.
            }
        }
        return found.get()
    }

    private fun convert(purchase: Purchase): StorePurchase? {
        val productId = purchase.products.firstOrNull() ?: return null
        return StorePurchase(
            productId = productId,
            orderId = purchase.orderId,
            purchaseToken = purchase.purchaseToken,
            purchaseTime = purchase.purchaseTime,
            purchased = purchase.purchaseState == PURCHASED,
        )
    }

    companion object {
        // The library's own constants, as values: BillingResponseCode.OK,
        // ProductType.SUBS, Purchase.PurchaseState.PURCHASED.
        private const val OK = 0
        private const val SUBS = "subs"
        private const val PURCHASED = 1
        private const val TIMEOUT_MS = 8_000L

        /**
         * Null when the app has no Play Billing Library, or one older than
         * version 7 (the first with PendingPurchasesParams, which every
         * client must now be built with).
         */
        fun createOrNull(context: Context, log: (String) -> Unit): PurchaseSource? =
            try {
                Class.forName("com.android.billingclient.api.PendingPurchasesParams")
                Class.forName("com.android.billingclient.api.QueryPurchasesParams")
                PlayPurchaseSource(context, log)
            } catch (t: Throwable) {
                log("purchases: automatic tracking is off (Play Billing Library 7 or newer not found)")
                null
            }
    }
}

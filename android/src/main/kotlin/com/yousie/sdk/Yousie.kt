package com.yousie.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.yousie.sdk.internal.Engine
import com.yousie.sdk.internal.FixedReferrerSource
import com.yousie.sdk.internal.HttpApi
import com.yousie.sdk.internal.PlayPurchaseSource
import com.yousie.sdk.internal.PlayReferrerSource
import com.yousie.sdk.internal.PrefsStore
import com.yousie.sdk.internal.Purchases
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Yousie attribution for Android: tells Yousie that this install came from
 * a creator's link, and later that it bought a subscription, so the creator
 * is paid for both.
 *
 * Call [init] once, as early as you can (`Application.onCreate`). That is
 * the whole integration for an app that needs no consent form. Every method
 * returns at once, does its work on a background thread, and never throws.
 */
object Yousie {
    /** The version of this SDK. */
    const val VERSION = "1.0.0"

    private const val TAG = "Yousie"
    private val SDK_KEY = Regex("^ysk_[A-Za-z0-9]{32}$")

    private val lock = Any()

    @Volatile
    private var engine: Engine? = null
    private var worker: ExecutorService? = null

    @Volatile
    private var logging = false

    // Set by a wrapper built from these sources (the Flutter plugin), so a
    // request says which package made it.
    @Volatile
    internal var wrapper: String? = null

    /**
     * Starts the SDK. On the first launch it reads the Play install
     * referrer; when the install came through a creator's link and
     * [consent] allows it, the install is reported.
     *
     * A second call in the same process only updates the consent.
     *
     * @param sdkKey the app's public SDK key from the Yousie dashboard
     *   (`ysk_` and 32 characters). It is not a secret.
     * @param consent what the user allows right now; see [YousieConsent].
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        context: Context,
        sdkKey: String,
        consent: YousieConsent,
        options: YousieOptions = YousieOptions(),
    ) {
        try {
            val started = synchronized(lock) {
                if (engine != null) return@synchronized false
                logging = options.logging
                val key = sdkKey.trim()
                if (!SDK_KEY.matches(key)) {
                    // Said even with logging off: this is the developer's
                    // mistake to see, and it turns the whole SDK off.
                    Log.w(TAG, "The SDK key is not a Yousie SDK key (ysk_ and 32 letters or digits). Nothing is tracked.")
                    return
                }
                val app = context.applicationContext ?: context
                val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                val debugReferrer = if (debuggable) options.debugReferrer?.trim().orEmpty() else ""
                val apiUrl = if (debuggable) options.debugApiUrl else null
                val created = Engine(
                    sdkKey = key,
                    store = PrefsStore(app),
                    referrer = if (debugReferrer.isNotEmpty()) FixedReferrerSource(debugReferrer) else PlayReferrerSource(app),
                    api = lazyApi(apiUrl),
                    purchases = {
                        if (options.autoTrackPurchases) PlayPurchaseSource.createOrNull(app, ::log) else null
                    },
                    appVersion = { appVersion(app) },
                    now = System::currentTimeMillis,
                    log = ::log,
                    debugReset = debuggable && options.debugReset,
                )
                created.consent = consent
                engine = created
                worker = Executors.newSingleThreadExecutor { task ->
                    Thread(task, "yousie").apply { isDaemon = true }
                }
                if (options.autoTrackPurchases) watchForeground(app, created)
                true
            }
            if (!started) {
                setConsent(consent)
                return
            }
            post { it.launch() }
        } catch (t: Throwable) {
            Log.w(TAG, "init failed", t)
        }
    }

    /**
     * States the user's answer to the consent question, whenever it is
     * known or changes. Stating the same answer again does nothing.
     */
    @JvmStatic
    fun setConsent(consent: YousieConsent) {
        try {
            val current = engine ?: return
            if (current.consent == consent) return
            current.consent = consent
            post { it.consentChanged() }
        } catch (t: Throwable) {
            Log.w(TAG, "setConsent failed", t)
        }
    }

    /**
     * Reports a subscription the user has just bought. Call it for a fresh
     * purchase only, not when a purchase is restored.
     *
     * Nothing is stored or sent unless the install came through a creator's
     * link. Reporting the same purchase twice is safe: Yousie keeps one.
     *
     * @param productId the store's product id.
     * @param orderId the store's order id (`Purchase.getOrderId()`).
     * @param purchaseToken only used when there is no order id, and then
     *   only as a hash: the token itself is never stored or sent.
     * @param price the plan's price in [currency]; for a trial, what the
     *   trial becomes. Sent only together with a three-letter currency code.
     * @param trial whether the purchase starts a free trial.
     */
    @JvmStatic
    @JvmOverloads
    fun trackSubscription(
        productId: String,
        orderId: String?,
        purchaseToken: String? = null,
        price: Double? = null,
        currency: String? = null,
        trial: Boolean = false,
    ) {
        try {
            val current = engine
            if (current == null) {
                log("trackSubscription: call Yousie.init first")
                return
            }
            val entry = Purchases.entry(
                productId = productId,
                trial = trial,
                at = System.currentTimeMillis(),
                orderId = orderId,
                purchaseToken = purchaseToken,
                price = price,
                currency = currency,
                auto = false,
            )
            if (entry == null) {
                log("trackSubscription: no order id and no purchase token, nothing names the purchase")
                return
            }
            // Noted twice, queued once.
            //
            // NOW, on the caller's thread: the worker may be seconds into a
            // request, and until its turn comes the purchase would exist
            // only in memory, lost if the process ends first.
            //
            // IN TURN, on the worker: the pass whose answer stands (a
            // referrer read or an install report that was in the air may
            // have changed it), and the only one that posts.
            val queued = current.note(entry)
            post {
                if (queued || it.note(entry)) it.purchaseNoted()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "trackSubscription failed", t)
        }
    }

    // ─── Internals ──────────────────────────────────────────────────────

    /** Runs [task] on the worker, after everything queued before it. */
    private fun post(task: (Engine) -> Unit) {
        val current = engine ?: return
        val thread = worker ?: return
        thread.execute {
            try {
                task(current)
            } catch (t: Throwable) {
                // Nothing here may reach the app.
                if (logging) Log.w(TAG, "failed", t)
            }
        }
    }

    private fun log(message: String) {
        if (logging) Log.d(TAG, message)
    }

    private fun lazyApi(baseUrl: String?): () -> HttpApi {
        var api: HttpApi? = null
        return {
            api ?: HttpApi(baseUrl, userAgent(), ::log).also { api = it }
        }
    }

    private fun userAgent(): String {
        val name = wrapper
        return "yousie-sdk-android/$VERSION" + if (name.isNullOrEmpty()) "" else " $name"
    }

    private fun appVersion(context: Context): String? =
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = info.versionName.orEmpty()
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            if (name.isEmpty()) null else "$name+$code"
        } catch (t: Throwable) {
            null
        }

    /**
     * A purchase ends with the app coming back to the foreground, so that
     * is when automatic tracking looks. For an install without a Yousie
     * link the callback reads one flag and returns.
     */
    private fun watchForeground(context: Context, engine: Engine) {
        val app = context as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (engine.watchesPurchases) post { it.resumed() }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {}

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

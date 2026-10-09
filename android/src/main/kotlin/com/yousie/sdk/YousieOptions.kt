package com.yousie.sdk

/**
 * Optional settings of [Yousie.init]. The defaults are right for production.
 *
 * The three `debug…` settings only work in a debuggable build. A release
 * build ignores them, so one left in by mistake changes nothing for users.
 */
class YousieOptions @JvmOverloads constructor(
    /**
     * Read the app's own Google Play subscriptions and report a new one
     * without any call from the app. Only on installs that came through a
     * creator's link, and only when the app has Play Billing Library 7 or
     * newer. An automatic report has no price and no trial flag: call
     * [Yousie.trackSubscription] to send those.
     */
    val autoTrackPurchases: Boolean = true,

    /** Print what the SDK does to logcat, under the tag `Yousie`. */
    val logging: Boolean = false,

    /**
     * Debuggable builds only. Used instead of the Play install referrer, so
     * a build that was not installed from Play can play an install that came
     * through a link:
     * `utm_source=yousie&utm_medium=affiliate&utm_campaign=<code>&yclid=<click id>`.
     */
    val debugReferrer: String? = null,

    /**
     * Debuggable builds only. Forget everything the SDK saved, each time
     * [Yousie.init] runs, so the whole flow replays on the same install.
     */
    val debugReset: Boolean = false,

    /**
     * Debuggable builds only. The origin the reports are sent to instead of
     * `https://yousie.com`.
     */
    val debugApiUrl: String? = null,
)

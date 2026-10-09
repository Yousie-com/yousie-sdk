package com.yousie.sdk.internal

import android.content.Context
import android.content.SharedPreferences

/** The few values the SDK keeps on the phone. */
internal interface Store {
    fun string(key: String): String?
    fun long(key: String): Long?
    fun flag(key: String): Boolean
    fun put(key: String, value: String)
    fun put(key: String, value: Long)
    fun put(key: String, value: Boolean)
    fun remove(vararg keys: String)
}

/**
 * Everything the SDK saves, in its own preferences file (`com.yousie.sdk`).
 * Nothing is written for an install that did not come through a Yousie link,
 * except [REFERRER_CHECKED].
 *
 * Like every preference they travel with Android's Auto Backup to a
 * reinstall and to a new phone, so the marks follow the person: a reinstall
 * that restores them is not reported a second time, and an attributed
 * install's purchase is still reported under the same install id.
 */
internal object Keys {
    /** Boolean: the Play install referrer has been read on this install. */
    const val REFERRER_CHECKED = "referrer_checked"

    /**
     * Long, epoch ms: when Play was first asked and could not be reached.
     * The question is asked again on each launch for a week after it.
     */
    const val REFERRER_ASKED_AT = "referrer_asked_at"

    /**
     * String: the click id of the link this install came through. Its
     * presence is the "install report owed" state: kept while consent is
     * unknown and while the report gets no answer, removed once Yousie has
     * answered, the user has refused, or a week has passed.
     */
    const val CLICK_ID = "click_id"

    /** Long, epoch ms: when [CLICK_ID] was stored. */
    const val CLICK_ID_AT = "click_id_at"

    /**
     * Long, epoch ms: when Play started this install. Automatic purchase
     * tracking only reports a subscription bought after it, so one the user
     * already owned before a reinstall is never credited to a creator.
     */
    const val INSTALL_BEGIN_AT = "install_begin_at"

    /**
     * String: a random UUID made for Yousie alone, the `install_id` of every
     * request. Derived from nothing on the device.
     */
    const val INSTALL_ID = "install_id"

    /**
     * String: Yousie's own name for this install (`ins_…`), stored when it
     * answered that the install is attributed. Its presence is what
     * "attributed" means on the phone.
     */
    const val INSTALL_REF = "install_ref"

    /** String, JSON list: subscription purchases not yet delivered. */
    const val OUTBOX = "outbox"

    /**
     * String, JSON list: the purchases automatic tracking has already
     * queued once, so one Yousie never answers is not queued forever.
     */
    const val AUTO_SEEN = "auto_seen"

    /**
     * Boolean: Yousie has answered a subscription report for this install.
     * It books one subscription per install, so automatic tracking stops.
     */
    const val SUBSCRIPTION_SETTLED = "subscription_settled"

    /**
     * String: the click id of the last debug run. Written only under
     * YousieOptions.debugReset, which keeps the install id while the same
     * test click is replayed and makes a new one for another click.
     */
    const val DEBUG_CLICK = "debug_click"

    val ALL = arrayOf(
        REFERRER_CHECKED,
        REFERRER_ASKED_AT,
        CLICK_ID,
        CLICK_ID_AT,
        INSTALL_BEGIN_AT,
        INSTALL_ID,
        INSTALL_REF,
        OUTBOX,
        AUTO_SEEN,
        SUBSCRIPTION_SETTLED,
        DEBUG_CLICK,
    )
}

internal class PrefsStore(context: Context) : Store {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // A value of the wrong type (a file edited by hand, a future version's
    // write) reads as absent rather than crashing the app.
    override fun string(key: String): String? =
        try {
            prefs.getString(key, null)
        } catch (e: ClassCastException) {
            null
        }

    override fun long(key: String): Long? =
        try {
            if (prefs.contains(key)) prefs.getLong(key, 0L) else null
        } catch (e: ClassCastException) {
            null
        }

    override fun flag(key: String): Boolean =
        try {
            prefs.getBoolean(key, false)
        } catch (e: ClassCastException) {
            false
        }

    // apply(): the value is readable at once from every thread, and the
    // write to disk never blocks the caller.
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()

    override fun put(key: String, value: Long) = prefs.edit().putLong(key, value).apply()

    override fun put(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    override fun remove(vararg keys: String) {
        val editor = prefs.edit()
        for (key in keys) editor.remove(key)
        editor.apply()
    }

    private companion object {
        const val FILE = "com.yousie.sdk"
    }
}

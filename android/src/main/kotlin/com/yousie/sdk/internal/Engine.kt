package com.yousie.sdk.internal

import com.yousie.sdk.YousieConsent
import java.util.UUID

/** What a read of the Play install referrer gave. */
internal sealed class ReferrerRead {
    /**
     * Play answered. [referrer] is null when it has none to give (no Play
     * Store, a feature it does not support): asking again will not change
     * that. [installBeginMs] is when Play started the install, on Google's
     * clock when it gave one.
     */
    class Value(val referrer: String?, val installBeginMs: Long?) : ReferrerRead()

    /** Play's service could not be reached. Ask again on a later launch. */
    object Unavailable : ReferrerRead()
}

internal interface ReferrerSource {
    /** Blocks until Play answers or a few seconds have passed. */
    fun read(): ReferrerRead
}

/**
 * Yousie attribution for one app process: tells Yousie that this install
 * came from a creator's link, and later that it bought a subscription.
 *
 * A creator's link records a click and sends the phone to Play with the
 * click id in the install referrer (`…&yclid=<22 base62>`). On the first
 * launch [launch] reads that referrer and, when it carries a click id, posts
 * ONE install report. Yousie answers whether the install is attributed; only
 * an attributed install ever reports a purchase.
 *
 * **Everyone else pays nothing.** An install that did not come through a
 * Yousie link costs one referrer read on one launch. Every launch after that
 * is a few preference reads and a return: no request, no billing connection.
 *
 * **Consent.** While it is [YousieConsent.UNKNOWN] the click id waits and
 * nothing is sent. [YousieConsent.GRANTED] sends the report.
 * [YousieConsent.DENIED] deletes the click id, for good.
 *
 * **Identity.** The `install_id` sent to Yousie is a random UUID made for
 * Yousie alone. No device or advertising id is read. A purchase token is
 * only ever hashed (Purchases.externalIdFor).
 *
 * **Retries are bounded, and there are no timers.** Work happens at launch,
 * on a consent change, when a purchase is noted and, for an install with a
 * click id only, when the app comes back to the foreground. A report that
 * got no answer is tried again on the next launch and given up a week after
 * the click id was stored. A purchase waits in a small persisted outbox
 * ([MAX_OUTBOX] entries, each given up after a week).
 *
 * Every method except [note] and [takesPurchases] runs on the SDK's one
 * worker thread, so no two of them ever overlap.
 */
internal class Engine(
    private val sdkKey: String,
    private val store: Store,
    private val referrer: ReferrerSource,
    /** Built on first use: an install with nothing to report never has one. */
    private val api: () -> Api,
    /** Null when automatic purchase tracking is off or cannot work here. */
    private val purchases: () -> PurchaseSource?,
    /** `2.14.3+458`, or null when the platform will not say. */
    private val appVersion: () -> String?,
    /** The device clock, epoch ms. */
    private val now: () -> Long,
    private val log: (String) -> Unit,
    private val debugReset: Boolean = false,
    private val newInstallId: () -> String = { UUID.randomUUID().toString() },
) {
    @Volatile
    var consent: YousieConsent = YousieConsent.UNKNOWN

    /**
     * Whether a return to the foreground is worth a look at Play Billing:
     * false for every install without a Yousie link, so theirs costs nothing.
     */
    @Volatile
    var watchesPurchases: Boolean = false
        private set

    // This launch's consent answer has been acted on. Without it, a report
    // Yousie did not answer would be posted again by every later pass.
    private var settled = false

    private var lastStoreCheck: Long? = null
    private var storeCheckGap = MIN_STORE_CHECK_GAP_MS
    private var purchaseSource: PurchaseSource? = null
    private var purchaseSourceAsked = false

    // The outbox is noted from the caller's thread and rewritten by the
    // worker: each reads and rewrites it under this lock, in one go.
    private val outboxLock = Any()

    // ─── Launch ─────────────────────────────────────────────────────────

    /** Once per process. */
    fun launch() {
        if (debugReset) {
            // Everything but the install id and the click it was made for:
            // under the same id Yousie answers a click it has already booked
            // the same way again, so one test click can be replayed as often
            // as needed (checkReferrer drops the id for another click).
            store.remove(*Keys.ALL.filter { it != Keys.INSTALL_ID && it != Keys.DEBUG_CLICK }.toTypedArray())
            log("debug reset: saved state cleared")
        }
        if (!store.flag(Keys.REFERRER_CHECKED)) {
            // Play could not be reached: the next launch asks again.
            if (!checkReferrer()) return
        }
        pump()
        checkStorePurchases()
    }

    /** The consent stated to the SDK changed. */
    fun consentChanged() {
        settled = false
        pump()
        checkStorePurchases()
    }

    /** The app came back to the foreground. */
    fun resumed() {
        checkStorePurchases()
    }

    /**
     * Reads this install's referrer, once, and keeps the click id it
     * carries. True when the question is settled; false when Play's service
     * was unreachable and a later launch should ask again.
     *
     * The click id is written before the once-flag, so a process killed
     * between the two asks Play again rather than losing the install.
     */
    private fun checkReferrer(): Boolean {
        val time = now()
        var value: ReferrerRead.Value? = null
        val askedAt = store.long(Keys.REFERRER_ASKED_AT)
        if (askedAt != null && askedAt <= time && pastWindow(askedAt, time)) {
            // Play has been unreachable on every launch for a week. A click
            // that old is outside the attribution window: stop asking.
            log("referrer: gave up on Play")
        } else {
            // A missing date, or one a corrected clock left in the future,
            // starts the week now.
            if (askedAt == null || askedAt > time) store.put(Keys.REFERRER_ASKED_AT, time)
            when (val read = referrer.read()) {
                is ReferrerRead.Unavailable -> {
                    log("referrer: Play could not be reached, the next launch asks again")
                    return false
                }
                is ReferrerRead.Value -> value = read
            }
        }
        val clickId = ClickId.fromReferrer(value?.referrer)
        if (debugReset) {
            // Another test click is another install.
            if (clickId != store.string(Keys.DEBUG_CLICK)) store.remove(Keys.INSTALL_ID)
            if (clickId == null) store.remove(Keys.DEBUG_CLICK) else store.put(Keys.DEBUG_CLICK, clickId)
        }
        if (clickId != null) {
            store.put(Keys.CLICK_ID, clickId)
            store.put(Keys.CLICK_ID_AT, time)
            // Google's own clock when Play gave it. Otherwise the phone's,
            // set back an hour so a clock that runs fast cannot hide a
            // purchase made in the first minutes.
            val begin = value?.installBeginMs
            store.put(Keys.INSTALL_BEGIN_AT, if (begin != null && begin > 0) begin else time - CLOCK_SLACK_MS)
        }
        store.put(Keys.REFERRER_CHECKED, true)
        store.remove(Keys.REFERRER_ASKED_AT)
        log("referrer checked: " + if (clickId == null) "no click id" else "click id $clickId")
        return true
    }

    private fun pastWindow(since: Long, time: Long): Boolean = time - since > RETRY_WINDOW_MS

    // ─── The pass over what is owed ─────────────────────────────────────

    /**
     * The install report while a click id is stored, then the outbox of an
     * attributed install, which is where a purchase made before the report
     * was answered goes out, in the same pass that attributes it.
     */
    private fun pump() {
        if (settled) return
        val wasAttributed = attributed()
        var clickId = store.string(Keys.CLICK_ID)
        if (clickId != null && wasAttributed) {
            // Killed between storing the answer and dropping the click id.
            forgetClick()
            clickId = null
        }
        if (clickId != null && clickExpired()) {
            log("install: gave up, the click is too old")
            forgetInstall()
            clickId = null
        }
        val queued = wasAttributed && readOutbox().isNotEmpty()
        if (clickId == null && !queued) {
            // Where every launch of an install without a Yousie link ends,
            // and every launch of an attributed one with nothing to send.
            settled = true
            refreshWatch()
            return
        }

        when (consent) {
            YousieConsent.UNKNOWN -> {
                // Kept, and looked at again when the answer is stated.
                log("consent unknown: waiting for setConsent")
                refreshWatch()
                return
            }
            YousieConsent.DENIED -> {
                settled = true
                // For good: the click id is gone, and with it any purchase
                // kept behind the report, so a later yes has nothing to
                // send. An attributed install keeps its mark and only loses
                // what was queued under the earlier yes.
                if (clickId != null) forgetInstall()
                store.remove(Keys.OUTBOX)
                log("consent denied: forgotten")
                refreshWatch()
                return
            }
            YousieConsent.GRANTED -> settled = true
        }

        if (clickId != null) reportInstall(clickId)
        if (attributed()) flush()
        refreshWatch()
    }

    // ─── Install report ─────────────────────────────────────────────────

    /** Whether Yousie has answered that this install is attributed. */
    private fun attributed(): Boolean = store.string(Keys.INSTALL_REF) != null

    /**
     * Whether the stored click id is older than a week. A missing date, or
     * one a corrected clock left in the future, starts the week now, so the
     * retries stay bounded either way.
     */
    private fun clickExpired(): Boolean {
        val time = now()
        val at = store.long(Keys.CLICK_ID_AT)
        if (at == null || at > time) {
            store.put(Keys.CLICK_ID_AT, time)
            return false
        }
        return pastWindow(at, time)
    }

    private fun forgetClick() {
        store.remove(Keys.CLICK_ID, Keys.CLICK_ID_AT)
    }

    /**
     * The end of an install that will not be attributed: the click id, the
     * id made for Yousie and any purchase that was waiting for the report
     * all go, and nothing is asked or sent again.
     */
    private fun forgetInstall() {
        store.remove(
            Keys.CLICK_ID,
            Keys.CLICK_ID_AT,
            Keys.INSTALL_BEGIN_AT,
            Keys.INSTALL_ID,
            Keys.OUTBOX,
            Keys.AUTO_SEEN,
        )
    }

    /**
     * One `POST /api/v1/installs` for [clickId]. An answer settles it either
     * way; no answer leaves the click id for the next launch.
     */
    private fun reportInstall(clickId: String) {
        // Made here, on the first report, and kept: the route is idempotent
        // on it, so a retry after a lost answer is booked once.
        var installId = store.string(Keys.INSTALL_ID)
        if (installId.isNullOrEmpty()) {
            installId = newInstallId()
            store.put(Keys.INSTALL_ID, installId)
        }
        val answer = api().reportInstall(sdkKey, clickId, installId, appVersion())
        if (answer.delivery != Delivery.ANSWERED) {
            log("install: no answer (${answer.status ?: "offline"}), the next launch tries again")
            return
        }
        if (answer.attributed) {
            // The mark first, the click id after: see pump for a kill between.
            store.put(Keys.INSTALL_REF, answer.installRef.orEmpty())
            forgetClick()
            log("install: attributed ${answer.installRef.orEmpty()}")
        } else {
            forgetInstall()
            log("install: not attributed (${answer.reason})")
        }
    }

    // ─── Subscription purchases ─────────────────────────────────────────

    /**
     * Whether a purchase made at this moment belongs in the outbox: an
     * attributed install with consent granted, or an install whose report is
     * still owed while nobody has said no. A few reads of what is already in
     * memory, so it can be asked from any thread.
     */
    fun takesPurchases(): Boolean {
        val stated = consent
        if (attributed()) return stated == YousieConsent.GRANTED
        return store.string(Keys.CLICK_ID) != null && stated != YousieConsent.DENIED
    }

    /**
     * Notes [entry] in the outbox, from any thread, and returns whether it
     * is there. Nothing is posted here.
     *
     * A purchase made while the install report is still owed waits unsent:
     * the first session is where both the report and most purchases happen,
     * and a trial started before the report got through would otherwise
     * never be credited. It goes out right behind the report once Yousie
     * answers "attributed", and is deleted with the click id if the install
     * ends any other way.
     *
     * The same purchase noted twice is one purchase. One the app reports
     * itself replaces what automatic tracking noted, since it knows the
     * price and the trial.
     */
    fun note(entry: OutboxEntry): Boolean {
        if (!takesPurchases()) return false
        synchronized(outboxLock) {
            val outbox = readOutbox()
            val index = outbox.indexOfFirst { it.externalId == entry.externalId }
            if (index >= 0) {
                val known = outbox[index]
                if (known.auto && !entry.auto) {
                    entry.at = known.at
                    outbox[index] = entry
                    writeOutbox(outbox)
                }
                return true
            }
            outbox.add(entry)
            writeOutbox(outbox)
        }
        if (!attributed()) log("purchase ${entry.externalId}: kept until the install report is answered")
        return true
    }

    /** After a purchase was noted: posts the outbox when it may go out. */
    fun purchaseNoted() {
        if (attributed() && consent == YousieConsent.GRANTED) flush()
        refreshWatch()
    }

    /**
     * Posts what is queued, oldest first. An entry leaves on Yousie's answer
     * (recorded or not: it will say the same again), or once it is older
     * than a week. One that gets a response which is not an answer stays and
     * the next is tried; when nothing comes back at all the rest would fare
     * no better, so they wait for the next flush untried.
     */
    private fun flush() {
        val outbox = readOutbox()
        if (outbox.isEmpty()) return
        val installId = store.string(Keys.INSTALL_ID).orEmpty()
        if (installId.isEmpty()) {
            // Nothing to send them under: cannot be an attributed install's.
            store.remove(Keys.OUTBOX)
            return
        }
        val time = now()
        val kept = ArrayList<OutboxEntry>()
        var reachable = true
        var answered = false
        for (entry in outbox) {
            if (entry.at > time) {
                // A corrected clock left the date in the future: the week
                // starts now, so the entry cannot outlive it by more.
                entry.at = time
            } else if (pastWindow(entry.at, time)) {
                continue
            }
            if (!reachable) {
                kept.add(entry)
                continue
            }
            val answer = api().reportSubscription(sdkKey, installId, entry)
            when (answer.delivery) {
                Delivery.ANSWERED -> {
                    answered = true
                    log(
                        "purchase ${entry.externalId}: " +
                            (if (answer.recorded) "recorded" else "not recorded") +
                            (if (answer.reason == null) "" else " (${answer.reason})"),
                    )
                }
                Delivery.REFUSED -> kept.add(entry)
                Delivery.UNREACHABLE -> {
                    kept.add(entry)
                    reachable = false
                }
            }
        }
        // A purchase noted while the posts above were in the air is in the
        // store and not in the list this pass started from: it stays, for
        // the flush its own turn brings.
        synchronized(outboxLock) {
            val current = readOutbox()
            val seen = outbox.mapTo(HashSet()) { it.externalId }
            for (i in kept.indices) {
                // Reported by the app itself in the meantime: its fuller
                // version is the one that waits.
                val fresh = current.firstOrNull { it.externalId == kept[i].externalId } ?: continue
                if (kept[i].auto && !fresh.auto) {
                    fresh.at = kept[i].at
                    kept[i] = fresh
                }
            }
            for (late in current) {
                if (late.externalId !in seen) kept.add(late)
            }
            writeOutbox(kept)
        }
        // Yousie books one subscription per install, so once it has answered
        // one report there is nothing left for automatic tracking to find.
        if (answered) store.put(Keys.SUBSCRIPTION_SETTLED, true)
    }

    private fun readOutbox(): MutableList<OutboxEntry> = Purchases.readOutbox(store.string(Keys.OUTBOX))

    /** Past [MAX_OUTBOX] the oldest go. */
    private fun writeOutbox(entries: List<OutboxEntry>) {
        val kept = if (entries.size > MAX_OUTBOX) entries.subList(entries.size - MAX_OUTBOX, entries.size) else entries
        if (kept.isEmpty()) store.remove(Keys.OUTBOX) else store.put(Keys.OUTBOX, Purchases.writeOutbox(kept))
    }

    // ─── Automatic purchase tracking ────────────────────────────────────

    private fun source(): PurchaseSource? {
        if (!purchaseSourceAsked) {
            purchaseSourceAsked = true
            purchaseSource = purchases()
        }
        return purchaseSource
    }

    private fun refreshWatch() {
        watchesPurchases = !store.flag(Keys.SUBSCRIPTION_SETTLED) &&
            consent != YousieConsent.DENIED &&
            (attributed() || store.string(Keys.CLICK_ID) != null) &&
            source() != null
    }

    /**
     * Looks at the subscriptions the app owns and notes a new one, for an
     * install that came through a link and has no subscription booked yet.
     *
     * Only a purchase made after this install began counts: a subscription
     * the user already owned before a reinstall is not a creator's doing.
     * What Play Billing says about a purchase does not include its price or
     * whether it is a trial, so neither is sent.
     */
    private fun checkStorePurchases() {
        if (store.flag(Keys.SUBSCRIPTION_SETTLED) || !takesPurchases()) {
            refreshWatch()
            return
        }
        val billing = source() ?: return
        val begin = store.long(Keys.INSTALL_BEGIN_AT) ?: return
        val time = now()
        // The launch, the consent answer and the first frame can all ask
        // within the same second.
        val last = lastStoreCheck
        if (last != null && time >= last && time - last < storeCheckGap) return
        lastStoreCheck = time

        val owned = billing.subscriptions()
        if (owned == null) {
            // A phone where Play Billing does not answer (no Play account,
            // no Play Store) takes seconds to say so: not on every return
            // to the foreground.
            storeCheckGap = FAILED_STORE_CHECK_GAP_MS
            log("purchases: Play Billing could not be asked")
            return
        }
        storeCheckGap = MIN_STORE_CHECK_GAP_MS
        val seen = Purchases.readIds(store.string(Keys.AUTO_SEEN))
        var added = false
        for (purchase in owned) {
            if (!purchase.purchased || purchase.purchaseTime < begin) continue
            val entry = Purchases.entry(
                productId = purchase.productId,
                trial = false,
                at = time,
                orderId = purchase.orderId,
                purchaseToken = purchase.purchaseToken,
                price = null,
                currency = null,
                auto = true,
            ) ?: continue
            if (entry.externalId in seen) continue
            if (!note(entry)) continue
            seen.add(entry.externalId)
            added = true
            log("purchases: found ${entry.externalId}")
        }
        if (!added) {
            refreshWatch()
            return
        }
        val kept = if (seen.size > MAX_SEEN) seen.subList(seen.size - MAX_SEEN, seen.size) else seen
        store.put(Keys.AUTO_SEEN, Purchases.writeIds(kept))
        // Only a new purchase posts: what an earlier pass left unanswered
        // waits for the next launch, like everything else.
        purchaseNoted()
    }

    companion object {
        /**
         * How long an unanswered question is asked again: the Play read
         * while Play is unreachable, the install report while Yousie is, a
         * queued purchase while its post fails.
         */
        const val RETRY_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

        /** The most purchases kept for a later flush. */
        const val MAX_OUTBOX = 5

        private const val MAX_SEEN = 20
        private const val MIN_STORE_CHECK_GAP_MS = 2_000L
        private const val FAILED_STORE_CHECK_GAP_MS = 60_000L
        private const val CLOCK_SLACK_MS = 60L * 60 * 1000
    }
}

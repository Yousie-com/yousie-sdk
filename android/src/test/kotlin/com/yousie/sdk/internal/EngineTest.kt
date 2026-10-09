package com.yousie.sdk.internal

import com.yousie.sdk.YousieConsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val KEY = "ysk_0123456789abcdefghijABCDEFGHIJ01"
private const val CLICK = "AbCdEfGhIjKlMnOpQrSt12"
private const val LINK = "utm_source=yousie&utm_medium=affiliate&utm_campaign=abcd2345&yclid=$CLICK"
private const val ORGANIC = "utm_source=google-play&utm_medium=organic"
private const val DAY = 24L * 60 * 60 * 1000

class EngineTest {
    private val store = FakeStore()
    private val referrer = FakeReferrer()
    private val api = FakeApi()
    private val billing = FakeBilling()
    private var clock = 1_000_000_000_000L
    private var billingAsked = 0
    private var installIds = 0

    /** A new process on the same phone: same store, fresh memory. */
    private fun process(consent: YousieConsent, autoTrack: Boolean = true): Engine {
        val engine = Engine(
            sdkKey = KEY,
            store = store,
            referrer = referrer,
            api = { api },
            purchases = {
                billingAsked++
                if (autoTrack) billing else null
            },
            appVersion = { "2.14.3+458" },
            now = { clock },
            log = {},
            newInstallId = { "install-${++installIds}" },
        )
        engine.consent = consent
        return engine
    }

    private fun attributedInstall(consent: YousieConsent = YousieConsent.GRANTED): Engine {
        referrer.next = ReferrerRead.Value(LINK, clock - 60_000)
        api.installAnswers += InstallAnswer.attributed("ins_0123456789abcdef")
        val engine = process(consent)
        engine.launch()
        return engine
    }

    private fun purchase(orderId: String, price: Double? = 29.99, trial: Boolean = true, auto: Boolean = false) =
        Purchases.entry("premium_yearly", trial, clock, orderId, "token-$orderId", price, if (price == null) null else "EUR", auto)!!

    // ─── Installs ───────────────────────────────────────────────────────

    @Test
    fun anInstallWithoutALinkCostsOneReferrerReadAndNothingElse() {
        referrer.next = ReferrerRead.Value(ORGANIC, clock)
        val first = process(YousieConsent.GRANTED)
        first.launch()

        assertEquals(1, referrer.reads)
        assertTrue(api.installs.isEmpty())
        assertEquals(setOf(Keys.REFERRER_CHECKED), store.keys())
        assertFalse(first.watchesPurchases)

        // Every later launch: no Play read, no request, no billing.
        val second = process(YousieConsent.GRANTED)
        second.launch()
        second.resumed()
        assertEquals(1, referrer.reads)
        assertTrue(api.installs.isEmpty())
        assertEquals(0, billingAsked)
        assertEquals(0, billing.reads)
    }

    @Test
    fun anInstallThroughALinkIsReportedOnce() {
        attributedInstall()

        assertEquals(1, api.installs.size)
        val report = api.installs[0]
        assertEquals(KEY, report.sdkKey)
        assertEquals(CLICK, report.clickId)
        assertEquals("install-1", report.installId)
        assertEquals("2.14.3+458", report.appVersion)
        assertEquals("ins_0123456789abcdef", store.string(Keys.INSTALL_REF))
        assertNull(store.string(Keys.CLICK_ID))

        process(YousieConsent.GRANTED).launch()
        assertEquals(1, api.installs.size)
        assertEquals(1, referrer.reads)
    }

    @Test
    fun whileConsentIsUnknownTheClickIdWaitsAndNothingIsSent() {
        referrer.next = ReferrerRead.Value(LINK, clock)
        api.installAnswers += InstallAnswer.attributed("ins_1")
        val engine = process(YousieConsent.UNKNOWN)
        engine.launch()

        assertTrue(api.installs.isEmpty())
        assertEquals(CLICK, store.string(Keys.CLICK_ID))

        engine.consent = YousieConsent.GRANTED
        engine.consentChanged()
        assertEquals(1, api.installs.size)
        assertEquals("ins_1", store.string(Keys.INSTALL_REF))
    }

    @Test
    fun aRefusalDeletesTheClickIdForGood() {
        referrer.next = ReferrerRead.Value(LINK, clock)
        val engine = process(YousieConsent.DENIED)
        engine.launch()

        assertTrue(api.installs.isEmpty())
        assertEquals(setOf(Keys.REFERRER_CHECKED), store.keys())

        // A later yes has nothing left to send.
        engine.consent = YousieConsent.GRANTED
        engine.consentChanged()
        process(YousieConsent.GRANTED).launch()
        assertTrue(api.installs.isEmpty())
        assertEquals(1, referrer.reads)
    }

    @Test
    fun aRefusalAfterWaitingAlsoForgets() {
        referrer.next = ReferrerRead.Value(LINK, clock)
        val engine = process(YousieConsent.UNKNOWN)
        engine.launch()
        engine.note(purchase("GPA.1"))
        assertNotNull(store.string(Keys.OUTBOX))

        engine.consent = YousieConsent.DENIED
        engine.consentChanged()
        assertEquals(setOf(Keys.REFERRER_CHECKED), store.keys())
        assertTrue(api.installs.isEmpty())
        assertTrue(api.events.isEmpty())
    }

    @Test
    fun anInstallYousieDoesNotAttributeIsForgotten() {
        referrer.next = ReferrerRead.Value(LINK, clock)
        api.installAnswers += InstallAnswer.notAttributed("click_used")
        val engine = process(YousieConsent.GRANTED)
        engine.launch()

        assertEquals(1, api.installs.size)
        assertEquals(setOf(Keys.REFERRER_CHECKED), store.keys())
        assertFalse(engine.takesPurchases())
        assertFalse(engine.watchesPurchases)
    }

    @Test
    fun aReportWithNoAnswerIsTriedAgainOnTheNextLaunchUnderTheSameInstallId() {
        referrer.next = ReferrerRead.Value(LINK, clock)
        api.installAnswers += InstallAnswer.unreachable()
        api.installAnswers += InstallAnswer.refused(503)
        api.installAnswers += InstallAnswer.attributed("ins_1")

        process(YousieConsent.GRANTED).launch()
        assertEquals(CLICK, store.string(Keys.CLICK_ID))
        clock += DAY
        process(YousieConsent.GRANTED).launch()
        assertEquals(CLICK, store.string(Keys.CLICK_ID))
        clock += DAY
        process(YousieConsent.GRANTED).launch()

        assertEquals(3, api.installs.size)
        assertEquals(setOf("install-1"), api.installs.map { it.installId }.toSet())
        assertEquals("ins_1", store.string(Keys.INSTALL_REF))
    }

    @Test
    fun aReportWithNoAnswerIsNotRepeatedWithinOneLaunch() {
        referrer.next = ReferrerRead.Value(LINK, clock)
        api.installAnswers += InstallAnswer.unreachable()
        val engine = process(YousieConsent.GRANTED)
        engine.launch()
        engine.resumed()
        engine.purchaseNoted()
        assertEquals(1, api.installs.size)
    }

    @Test
    fun aReportIsGivenUpAWeekAfterTheClick() {
        referrer.next = ReferrerRead.Value(LINK, clock)
        api.installAnswers += InstallAnswer.unreachable()
        process(YousieConsent.GRANTED).launch()

        clock += 7 * DAY + 1
        process(YousieConsent.GRANTED).launch()
        assertEquals(1, api.installs.size)
        assertEquals(setOf(Keys.REFERRER_CHECKED), store.keys())
    }

    @Test
    fun aClockSetBackDoesNotMakeTheWeekEndless() {
        referrer.next = ReferrerRead.Value(LINK, clock)
        api.installAnswers += InstallAnswer.unreachable()
        api.installAnswers += InstallAnswer.unreachable()
        process(YousieConsent.GRANTED).launch()

        // The clock is corrected to a year earlier: the week starts again.
        clock -= 365 * DAY
        process(YousieConsent.GRANTED).launch()
        assertEquals(clock, store.long(Keys.CLICK_ID_AT))
        clock += 7 * DAY + 1
        process(YousieConsent.GRANTED).launch()
        assertEquals(2, api.installs.size)
        assertNull(store.string(Keys.CLICK_ID))
    }

    @Test
    fun whenPlayCannotBeReachedTheNextLaunchAsksAgain() {
        referrer.next = ReferrerRead.Unavailable
        process(YousieConsent.GRANTED).launch()
        assertFalse(store.flag(Keys.REFERRER_CHECKED))
        assertEquals(clock, store.long(Keys.REFERRER_ASKED_AT))

        clock += DAY
        referrer.next = ReferrerRead.Value(LINK, clock)
        api.installAnswers += InstallAnswer.attributed("ins_1")
        process(YousieConsent.GRANTED).launch()
        assertEquals(2, referrer.reads)
        assertTrue(store.flag(Keys.REFERRER_CHECKED))
        assertNull(store.long(Keys.REFERRER_ASKED_AT))
        assertEquals("ins_1", store.string(Keys.INSTALL_REF))
    }

    @Test
    fun afterAWeekOfSilencePlayIsNotAskedAgain() {
        referrer.next = ReferrerRead.Unavailable
        process(YousieConsent.GRANTED).launch()
        clock += 7 * DAY + 1
        process(YousieConsent.GRANTED).launch()

        assertEquals(1, referrer.reads)
        assertEquals(setOf(Keys.REFERRER_CHECKED), store.keys())
    }

    @Test
    fun aPlayStoreWithNoReferrerSettlesTheQuestion() {
        referrer.next = ReferrerRead.Value(null, null)
        process(YousieConsent.GRANTED).launch()
        assertEquals(setOf(Keys.REFERRER_CHECKED), store.keys())
    }

    @Test
    fun aKillBetweenTheAnswerAndTheCleanUpIsRepaired() {
        attributedInstall()
        store.put(Keys.CLICK_ID, CLICK)
        store.put(Keys.CLICK_ID_AT, clock)

        process(YousieConsent.GRANTED).launch()
        assertNull(store.string(Keys.CLICK_ID))
        assertEquals(1, api.installs.size)
    }

    // ─── Purchases the app reports ──────────────────────────────────────

    @Test
    fun aPurchaseOfAnAttributedInstallIsPosted() {
        val engine = attributedInstall()
        api.eventAnswers += EventAnswer.answered(recorded = true)

        assertTrue(engine.note(purchase("GPA.1")))
        engine.purchaseNoted()

        assertEquals(1, api.events.size)
        val event = api.events[0]
        assertEquals(KEY, event.sdkKey)
        assertEquals("install-1", event.installId)
        assertEquals("GPA.1", event.purchase.externalId)
        assertEquals("premium_yearly", event.purchase.productId)
        assertEquals(29_990_000L, event.purchase.priceMicros)
        assertEquals("EUR", event.purchase.currency)
        assertTrue(event.purchase.trial)
        assertNull(store.string(Keys.OUTBOX))
        assertTrue(store.flag(Keys.SUBSCRIPTION_SETTLED))
    }

    @Test
    fun aPurchaseOfAnInstallWithoutALinkIsNotEvenStored() {
        referrer.next = ReferrerRead.Value(ORGANIC, clock)
        val engine = process(YousieConsent.GRANTED)
        engine.launch()

        assertFalse(engine.note(purchase("GPA.1")))
        engine.purchaseNoted()
        assertTrue(api.events.isEmpty())
        assertEquals(setOf(Keys.REFERRER_CHECKED), store.keys())
    }

    @Test
    fun aPurchaseMadeBeforeTheReportIsAnsweredFollowsIt() {
        referrer.next = ReferrerRead.Value(LINK, clock)
        val engine = process(YousieConsent.UNKNOWN)
        engine.launch()

        // The onboarding paywall, while the consent form is still open.
        assertTrue(engine.note(purchase("GPA.1")))
        engine.purchaseNoted()
        assertTrue(api.events.isEmpty())

        api.installAnswers += InstallAnswer.attributed("ins_1")
        api.eventAnswers += EventAnswer.answered(recorded = true)
        engine.consent = YousieConsent.GRANTED
        engine.consentChanged()

        assertEquals(1, api.installs.size)
        assertEquals(1, api.events.size)
        assertEquals("install-1", api.events[0].installId)
        assertNull(store.string(Keys.OUTBOX))
    }

    @Test
    fun aPurchaseKeptBehindAReportThatIsNotAttributedIsDeletedUnsent() {
        referrer.next = ReferrerRead.Value(LINK, clock)
        val engine = process(YousieConsent.UNKNOWN)
        engine.launch()
        engine.note(purchase("GPA.1"))

        api.installAnswers += InstallAnswer.notAttributed("expired")
        engine.consent = YousieConsent.GRANTED
        engine.consentChanged()
        assertTrue(api.events.isEmpty())
        assertEquals(setOf(Keys.REFERRER_CHECKED), store.keys())
    }

    @Test
    fun anAttributedInstallOnlyReportsPurchasesWhileConsentStands() {
        val engine = attributedInstall()
        engine.consent = YousieConsent.DENIED
        engine.consentChanged()

        assertFalse(engine.note(purchase("GPA.1")))
        engine.purchaseNoted()
        assertTrue(api.events.isEmpty())
        // The install keeps its mark.
        assertNotNull(store.string(Keys.INSTALL_REF))
    }

    @Test
    fun theSamePurchaseNotedTwiceIsOnePurchase() {
        val engine = attributedInstall()
        api.eventAnswers += EventAnswer.unreachable()
        engine.note(purchase("GPA.1"))
        engine.note(purchase("GPA.1"))
        engine.purchaseNoted()

        assertEquals(1, Purchases.readOutbox(store.string(Keys.OUTBOX)).size)
        assertEquals(1, api.events.size)
    }

    @Test
    fun aPurchaseYousieDoesNotAnswerWaitsAndIsGivenUpAfterAWeek() {
        val engine = attributedInstall()
        api.eventAnswers += EventAnswer.refused(500)
        engine.note(purchase("GPA.1"))
        engine.purchaseNoted()
        assertEquals(1, Purchases.readOutbox(store.string(Keys.OUTBOX)).size)
        assertFalse(store.flag(Keys.SUBSCRIPTION_SETTLED))

        // The next launch flushes it again.
        clock += DAY
        api.eventAnswers += EventAnswer.unreachable()
        process(YousieConsent.GRANTED).launch()
        assertEquals(2, api.events.size)
        assertEquals(1, Purchases.readOutbox(store.string(Keys.OUTBOX)).size)

        clock += 7 * DAY
        process(YousieConsent.GRANTED).launch()
        assertEquals(2, api.events.size)
        assertNull(store.string(Keys.OUTBOX))
    }

    @Test
    fun whenNothingComesBackTheRestOfTheOutboxIsNotTried() {
        val engine = attributedInstall()
        api.eventAnswers += EventAnswer.unreachable()
        engine.note(purchase("GPA.1"))
        engine.note(purchase("GPA.2"))
        engine.note(purchase("GPA.3"))
        engine.purchaseNoted()

        assertEquals(1, api.events.size)
        assertEquals(3, Purchases.readOutbox(store.string(Keys.OUTBOX)).size)
    }

    @Test
    fun aRefusedPurchaseDoesNotHoldUpTheNext() {
        val engine = attributedInstall()
        api.eventAnswers += EventAnswer.refused(422)
        api.eventAnswers += EventAnswer.answered(recorded = false, reason = "duplicate")
        engine.note(purchase("GPA.1"))
        engine.note(purchase("GPA.2"))
        engine.purchaseNoted()

        assertEquals(2, api.events.size)
        assertEquals(listOf("GPA.1"), Purchases.readOutbox(store.string(Keys.OUTBOX)).map { it.externalId })
    }

    @Test
    fun theOutboxKeepsTheNewestFive() {
        referrer.next = ReferrerRead.Value(LINK, clock)
        val engine = process(YousieConsent.UNKNOWN)
        engine.launch()
        for (i in 1..7) engine.note(purchase("GPA.$i"))

        assertEquals(
            listOf("GPA.3", "GPA.4", "GPA.5", "GPA.6", "GPA.7"),
            Purchases.readOutbox(store.string(Keys.OUTBOX)).map { it.externalId },
        )
    }

    // ─── Purchases found automatically ──────────────────────────────────

    @Test
    fun aNewSubscriptionIsFoundWithoutACallFromTheApp() {
        val engine = attributedInstall()
        assertTrue(engine.watchesPurchases)
        assertEquals(1, billing.reads) // the launch looked, and found nothing

        clock += 60_000
        billing.owned = listOf(StorePurchase("premium_yearly", "GPA.9", "token", clock - 5_000, purchased = true))
        api.eventAnswers += EventAnswer.answered(recorded = true)
        engine.resumed()

        assertEquals(1, api.events.size)
        val sent = api.events[0].purchase
        assertEquals("GPA.9", sent.externalId)
        assertEquals("premium_yearly", sent.productId)
        // Play Billing does not say the price or whether it is a trial.
        assertNull(sent.priceMicros)
        assertFalse(sent.trial)

        // Yousie books one subscription per install: nothing more to find.
        assertFalse(engine.watchesPurchases)
        clock += 60_000
        engine.resumed()
        process(YousieConsent.GRANTED).launch()
        assertEquals(2, billing.reads)
        assertEquals(1, api.events.size)
    }

    @Test
    fun aSubscriptionOwnedBeforeTheInstallIsNotACreatorsDoing() {
        referrer.next = ReferrerRead.Value(LINK, clock - 60_000)
        api.installAnswers += InstallAnswer.attributed("ins_1")
        billing.owned = listOf(
            StorePurchase("premium_yearly", "GPA.OLD", "t1", clock - 90 * DAY, purchased = true),
            StorePurchase("premium_yearly", "GPA.PENDING", "t2", clock, purchased = false),
        )
        process(YousieConsent.GRANTED).launch()

        assertEquals(1, billing.reads)
        assertTrue(api.events.isEmpty())
        assertNull(store.string(Keys.OUTBOX))
    }

    @Test
    fun aPurchaseFoundBeforeTheReportIsAnsweredFollowsIt() {
        referrer.next = ReferrerRead.Value(LINK, clock - 60_000)
        billing.owned = listOf(StorePurchase("premium_yearly", "GPA.9", "token", clock, purchased = true))
        val engine = process(YousieConsent.UNKNOWN)
        engine.launch()

        assertEquals(1, billing.reads)
        assertTrue(api.events.isEmpty())
        assertEquals(1, Purchases.readOutbox(store.string(Keys.OUTBOX)).size)

        api.installAnswers += InstallAnswer.attributed("ins_1")
        api.eventAnswers += EventAnswer.answered(recorded = true)
        engine.consent = YousieConsent.GRANTED
        engine.consentChanged()
        assertEquals(1, api.events.size)
    }

    @Test
    fun whatTheAppReportsReplacesWhatWasFound() {
        referrer.next = ReferrerRead.Value(LINK, clock - 60_000)
        billing.owned = listOf(StorePurchase("premium_yearly", "GPA.9", "token", clock, purchased = true))
        val engine = process(YousieConsent.UNKNOWN)
        engine.launch()

        // The app's own call comes after, with the price and the trial.
        engine.note(purchase("GPA.9", price = 29.99, trial = true))
        api.installAnswers += InstallAnswer.attributed("ins_1")
        api.eventAnswers += EventAnswer.answered(recorded = true)
        engine.consent = YousieConsent.GRANTED
        engine.consentChanged()

        assertEquals(1, api.events.size)
        assertEquals(29_990_000L, api.events[0].purchase.priceMicros)
        assertTrue(api.events[0].purchase.trial)
    }

    @Test
    fun whatWasFoundNeverReplacesWhatTheAppReported() {
        val engine = attributedInstall()
        api.eventAnswers += EventAnswer.unreachable()
        engine.note(purchase("GPA.9", price = 29.99, trial = true))
        engine.purchaseNoted()

        clock += 60_000
        billing.owned = listOf(StorePurchase("premium_yearly", "GPA.9", "token", clock - 1_000, purchased = true))
        api.eventAnswers += EventAnswer.answered(recorded = true)
        engine.resumed()

        assertEquals(2, api.events.size)
        assertEquals(29_990_000L, api.events[1].purchase.priceMicros)
        assertTrue(api.events[1].purchase.trial)
    }

    @Test
    fun aFoundPurchaseYousieNeverAnswersIsNotQueuedForEver() {
        val engine = attributedInstall()
        clock += 60_000
        billing.owned = listOf(StorePurchase("premium_yearly", "GPA.9", "token", clock - 1_000, purchased = true))
        api.eventAnswers += EventAnswer.refused(500)
        engine.resumed()
        assertEquals(1, api.events.size)

        // A week later it has left the outbox, and it is not found again.
        clock += 7 * DAY + 1
        val later = process(YousieConsent.GRANTED)
        later.launch()
        assertNull(store.string(Keys.OUTBOX))
        assertEquals(1, api.events.size)
    }

    @Test
    fun whenPlayBillingCannotBeAskedNothingIsLost() {
        val engine = attributedInstall()
        clock += 60_000
        billing.owned = null
        engine.resumed()
        assertTrue(api.events.isEmpty())
        assertTrue(engine.watchesPurchases)
        assertEquals(2, billing.reads)

        // It is not asked again on every return to the foreground.
        clock += 30_000
        engine.resumed()
        assertEquals(2, billing.reads)

        // A minute later it answers, and the purchase is found.
        clock += 31_000
        billing.owned = listOf(StorePurchase("premium_yearly", "GPA.9", "token", clock - 50_000, purchased = true))
        api.eventAnswers += EventAnswer.answered(recorded = true)
        engine.resumed()
        assertEquals(3, billing.reads)
        assertEquals(1, api.events.size)
    }

    @Test
    fun withoutPlayBillingTheInstallIsStillReported() {
        referrer.next = ReferrerRead.Value(LINK, clock)
        api.installAnswers += InstallAnswer.attributed("ins_1")
        val engine = process(YousieConsent.GRANTED, autoTrack = false)
        engine.launch()
        engine.resumed()

        assertEquals("ins_1", store.string(Keys.INSTALL_REF))
        assertFalse(engine.watchesPurchases)
        assertEquals(0, billing.reads)
        // The app can still report a purchase itself.
        api.eventAnswers += EventAnswer.answered(recorded = true)
        assertTrue(engine.note(purchase("GPA.1")))
        engine.purchaseNoted()
        assertEquals(1, api.events.size)
    }

    @Test
    fun checksCloseTogetherAskPlayBillingOnce() {
        val engine = attributedInstall()
        engine.resumed()
        engine.resumed()
        assertEquals(1, billing.reads)
        clock += 5_000
        engine.resumed()
        assertEquals(2, billing.reads)
    }

    // ─── Debug ──────────────────────────────────────────────────────────

    private fun debugStart(link: String) {
        referrer.next = ReferrerRead.Value(link, null)
        api.installAnswers += InstallAnswer.attributed("ins_debug")
        val engine = Engine(
            sdkKey = KEY,
            store = store,
            referrer = referrer,
            api = { api },
            purchases = { null },
            appVersion = { null },
            now = { clock },
            log = {},
            debugReset = true,
            newInstallId = { "install-${++installIds}" },
        )
        engine.consent = YousieConsent.GRANTED
        engine.launch()
    }

    @Test
    fun aDebugResetReplaysTheSameClickUnderTheSameInstallId() {
        debugStart(LINK)
        store.put(Keys.SUBSCRIPTION_SETTLED, true)
        debugStart(LINK)
        debugStart(LINK)

        assertEquals(3, referrer.reads)
        assertEquals(listOf("install-1", "install-1", "install-1"), api.installs.map { it.installId })
        assertEquals("ins_debug", store.string(Keys.INSTALL_REF))
        assertFalse(store.flag(Keys.SUBSCRIPTION_SETTLED))
    }

    @Test
    fun aDebugResetWithAnotherClickIsAnotherInstall() {
        debugStart(LINK)
        debugStart(LINK.replace(CLICK, "ZzZzZzZzZzZzZzZzZzZz99"))
        debugStart(ORGANIC)

        assertEquals(listOf("install-1", "install-2"), api.installs.map { it.installId })
        assertEquals(listOf(CLICK, "ZzZzZzZzZzZzZzZzZzZz99"), api.installs.map { it.clickId })
        assertEquals(setOf(Keys.REFERRER_CHECKED), store.keys())
    }

    @Test
    fun withoutADebugResetNothingOfItIsKept() {
        attributedInstall()
        assertNull(store.string(Keys.DEBUG_CLICK))
    }
}

// ─── Fakes ──────────────────────────────────────────────────────────────

private class FakeStore : Store {
    private val values = HashMap<String, Any>()

    fun keys(): Set<String> = values.keys.toSet()

    override fun string(key: String): String? = values[key] as? String

    override fun long(key: String): Long? = values[key] as? Long

    override fun flag(key: String): Boolean = values[key] == true

    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun put(key: String, value: Long) {
        values[key] = value
    }

    override fun put(key: String, value: Boolean) {
        values[key] = value
    }

    override fun remove(vararg keys: String) {
        for (key in keys) values.remove(key)
    }
}

private class FakeReferrer : ReferrerSource {
    var next: ReferrerRead = ReferrerRead.Value(null, null)
    var reads = 0

    override fun read(): ReferrerRead {
        reads++
        return next
    }
}

private class FakeBilling : PurchaseSource {
    var owned: List<StorePurchase>? = emptyList()
    var reads = 0

    override fun subscriptions(): List<StorePurchase>? {
        reads++
        return owned
    }
}

private class FakeApi : Api {
    class Install(val sdkKey: String, val clickId: String, val installId: String, val appVersion: String?)

    class Event(val sdkKey: String, val installId: String, val purchase: OutboxEntry)

    val installs = ArrayList<Install>()
    val events = ArrayList<Event>()
    val installAnswers = ArrayDeque<InstallAnswer>()
    val eventAnswers = ArrayDeque<EventAnswer>()

    override fun reportInstall(sdkKey: String, clickId: String, installId: String, appVersion: String?): InstallAnswer {
        installs += Install(sdkKey, clickId, installId, appVersion)
        return installAnswers.removeFirstOrNull() ?: error("an install report nobody expected")
    }

    override fun reportSubscription(sdkKey: String, installId: String, purchase: OutboxEntry): EventAnswer {
        events += Event(sdkKey, installId, purchase)
        return eventAnswers.removeFirstOrNull() ?: error("an event nobody expected")
    }
}

package com.yousie.sdk.internal

import com.yousie.sdk.YousieConsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The engine and the real HTTP client against a running Yousie server, for
 * whoever works on the SDK. Skipped unless a server is named:
 *
 *     ./gradlew :yousie:testDebugUnitTest \
 *         -Pyousie.e2e.url=http://127.0.0.1:8797 \
 *         -Pyousie.e2e.sdkKey=ysk_… \
 *         -Pyousie.e2e.clicks=<click id>,<click id>,<click id>
 *
 * Each click id is a fresh, unused one of a link to the app the SDK key
 * belongs to (open the link, read `yclid` in the store address), and at
 * least ten seconds old: the server refuses an install that follows its
 * click faster than a real one can (`too_fast`).
 */
class LocalServerTest {
    private val url = System.getProperty("yousie.e2e.url").orEmpty()
    private val sdkKey = System.getProperty("yousie.e2e.sdkKey").orEmpty()
    private val clicks = System.getProperty("yousie.e2e.clicks").orEmpty().split(',').filter { it.isNotBlank() }
    private val run = System.currentTimeMillis().toString(36).uppercase()

    @Before
    fun needsAServer() {
        assumeTrue("no test server named", url.isNotEmpty() && sdkKey.isNotEmpty() && clicks.size >= 3)
    }

    private class Phone(val store: MemoryStore = MemoryStore(), val billing: FixedBilling = FixedBilling())

    private fun engine(phone: Phone, clickId: String, consent: YousieConsent = YousieConsent.GRANTED): Engine {
        val api = HttpApi(url, "yousie-sdk-android/e2e", log = ::println)
        val engine = Engine(
            sdkKey = sdkKey,
            store = phone.store,
            referrer = FixedReferrerSource("utm_source=yousie&utm_medium=affiliate&utm_campaign=e2e&yclid=$clickId"),
            api = { api },
            purchases = { phone.billing },
            appVersion = { "1.0.0+1" },
            now = System::currentTimeMillis,
            log = ::println,
        )
        engine.consent = consent
        return engine
    }

    @Test
    fun anInstallIsAttributedAndItsSubscriptionRecorded() {
        val phone = Phone()
        val engine = engine(phone, clicks[0])
        engine.launch()

        val ref = phone.store.string(Keys.INSTALL_REF)
        assertTrue("install ref: $ref", ref != null && Regex("^ins_[A-Za-z0-9]{16}$").matches(ref))
        assertNull(phone.store.string(Keys.CLICK_ID))

        val purchase = Purchases.entry("premium_yearly", true, System.currentTimeMillis(), "E2E.$run.MANUAL", "token", 29.99, "eur", false)!!
        assertTrue(engine.note(purchase))
        engine.purchaseNoted()
        assertNull(phone.store.string(Keys.OUTBOX))
        assertTrue(phone.store.flag(Keys.SUBSCRIPTION_SETTLED))

        // The same click from another phone is refused, and that phone
        // forgets everything.
        val other = Phone()
        engine(other, clicks[0]).launch()
        assertEquals(setOf(Keys.REFERRER_CHECKED), other.store.keys())
    }

    @Test
    fun aRepeatedReportIsAnsweredTheSameWay() {
        val api = HttpApi(url, "yousie-sdk-android/e2e", log = ::println)
        val installId = "e2e-repeat-$run".lowercase()
        val first = api.reportInstall(sdkKey, clicks[1], installId, "1.0.0+1")
        val again = api.reportInstall(sdkKey, clicks[1], installId, "1.0.0+1")
        assertTrue(first.attributed)
        assertTrue(again.attributed)
        assertEquals(first.installRef, again.installRef)

        val purchase = OutboxEntry("E2E.$run.REPEAT", "premium_yearly", null, null, false, at = 0, auto = true)
        val recorded = api.reportSubscription(sdkKey, installId, purchase)
        val repeated = api.reportSubscription(sdkKey, installId, purchase)
        assertEquals(Delivery.ANSWERED, recorded.delivery)
        assertTrue(recorded.recorded)
        assertTrue(repeated.recorded)

        // One subscription per install: another order is a duplicate.
        val second = api.reportSubscription(sdkKey, installId, OutboxEntry("E2E.$run.SECOND", null, null, null, false, 0, true))
        assertEquals(Delivery.ANSWERED, second.delivery)
        assertFalse(second.recorded)
        assertEquals("duplicate", second.reason)
    }

    @Test
    fun aSubscriptionFoundInPlayBillingIsRecordedBehindTheReport() {
        val phone = Phone()
        phone.billing.owned = listOf(
            StorePurchase("premium_monthly", "E2E.$run.OLD", "t0", System.currentTimeMillis() - 90L * 24 * 3600 * 1000, true),
            StorePurchase("premium_yearly", "E2E.$run.AUTO", "t1", System.currentTimeMillis(), true),
        )
        // The consent form is still open when the purchase is found.
        val engine = engine(phone, clicks[2], YousieConsent.UNKNOWN)
        engine.launch()
        assertEquals(listOf("E2E.$run.AUTO"), Purchases.readOutbox(phone.store.string(Keys.OUTBOX)).map { it.externalId })

        engine.consent = YousieConsent.GRANTED
        engine.consentChanged()
        assertTrue(phone.store.string(Keys.INSTALL_REF) != null)
        assertNull(phone.store.string(Keys.OUTBOX))
        assertTrue(phone.store.flag(Keys.SUBSCRIPTION_SETTLED))
    }

    @Test
    fun aClickYousieDoesNotKnowIsNotAttributed() {
        val api = HttpApi(url, "yousie-sdk-android/e2e", log = ::println)
        val unknown = api.reportInstall(sdkKey, "Zz" + "0".repeat(20), "e2e-unknown-$run".lowercase(), null)
        assertEquals(Delivery.ANSWERED, unknown.delivery)
        assertFalse(unknown.attributed)
        assertEquals("unknown_click", unknown.reason)

        // A key that names no app is refused, which is not an answer.
        val badKey = api.reportInstall("ysk_" + "0".repeat(32), clicks[0], "e2e-badkey-$run".lowercase(), null)
        assertEquals(Delivery.REFUSED, badKey.delivery)

        val orphan = api.reportSubscription(sdkKey, "e2e-orphan-$run".lowercase(), OutboxEntry("E2E.$run.ORPHAN", null, null, null, false, 0, false))
        assertEquals(Delivery.ANSWERED, orphan.delivery)
        assertFalse(orphan.recorded)
        assertEquals("not_attributed", orphan.reason)
    }
}

internal class MemoryStore : Store {
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

internal class FixedBilling : PurchaseSource {
    var owned: List<StorePurchase>? = emptyList()

    override fun subscriptions(): List<StorePurchase>? = owned
}

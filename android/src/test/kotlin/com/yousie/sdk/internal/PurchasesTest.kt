package com.yousie.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchasesTest {
    @Test
    fun theOrderIdNamesThePurchase() {
        assertEquals("GPA.3345-1234-5678-90123", Purchases.externalIdFor("GPA.3345-1234-5678-90123", "token"))
        assertEquals("GPA.1", Purchases.externalIdFor("  GPA.1 ", null))
    }

    @Test
    fun withoutAnOrderIdAHashOfTheTokenNamesIt() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals("tok:ba7816bf8f01cfea414140de5dae2223", Purchases.externalIdFor(null, "abc"))
        assertEquals("tok:ba7816bf8f01cfea414140de5dae2223", Purchases.externalIdFor("", "abc"))
        // An order id the API would refuse (a space) is not sent as it stands.
        assertEquals("tok:ba7816bf8f01cfea414140de5dae2223", Purchases.externalIdFor("GPA 1", "abc"))
        assertEquals("tok:ba7816bf8f01cfea414140de5dae2223", Purchases.externalIdFor("x".repeat(129), "abc"))
    }

    @Test
    fun nothingNamesAPurchaseWithNeither() {
        assertNull(Purchases.externalIdFor(null, null))
        assertNull(Purchases.externalIdFor(" ", ""))
        assertNull(entry(orderId = null, purchaseToken = null))
    }

    @Test
    fun thePriceTravelsAsMicrosWithItsCurrency() {
        val entry = entry(price = 29.99, currency = "eur")!!
        assertEquals(29_990_000L, entry.priceMicros)
        assertEquals("EUR", entry.currency)
        assertEquals(4_990_000L, entry(price = 4.99, currency = "USD")!!.priceMicros)
        assertEquals(0L, entry(price = 0.0, currency = "USD")!!.priceMicros)
    }

    @Test
    fun aPriceThatIsNotAPriceIsLeftOut() {
        for (price in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1e9)) {
            val entry = entry(price = price, currency = "EUR")!!
            assertNull(entry.priceMicros)
            assertNull(entry.currency)
        }
        assertNull(entry(price = 9.99, currency = null)!!.priceMicros)
        assertNull(entry(price = 9.99, currency = "EURO")!!.priceMicros)
        assertNull(entry(price = null, currency = "EUR")!!.currency)
    }

    @Test
    fun aProductIdTheApiWouldRefuseIsLeftOut() {
        assertEquals("premium_yearly", entry(productId = " premium_yearly ")!!.productId)
        assertNull(entry(productId = "")!!.productId)
        assertNull(entry(productId = "two words")!!.productId)
        assertNull(entry(productId = "x".repeat(129))!!.productId)
    }

    @Test
    fun theOutboxSurvivesBeingStored() {
        val entries = listOf(
            entry(orderId = "GPA.1", price = 2.99, currency = "EUR", trial = true)!!,
            entry(orderId = "GPA.2", auto = true)!!,
        )
        val read = Purchases.readOutbox(Purchases.writeOutbox(entries))
        assertEquals(2, read.size)
        assertEquals("GPA.1", read[0].externalId)
        assertEquals("premium_yearly", read[0].productId)
        assertEquals(2_990_000L, read[0].priceMicros)
        assertEquals("EUR", read[0].currency)
        assertTrue(read[0].trial)
        assertFalse(read[0].auto)
        assertEquals(1_000L, read[0].at)
        assertTrue(read[1].auto)
        assertNull(read[1].priceMicros)
        assertFalse(read[1].trial)
    }

    @Test
    fun aCorruptOutboxStartsClean() {
        assertTrue(Purchases.readOutbox(null).isEmpty())
        assertTrue(Purchases.readOutbox("").isEmpty())
        assertTrue(Purchases.readOutbox("{not json").isEmpty())
        assertTrue(Purchases.readOutbox("{\"a\":1}").isEmpty())
        // Entries that name nothing, or carry no date, are dropped one by one.
        val read = Purchases.readOutbox("[1, {\"external_id\":\"\"}, {\"external_id\":\"A\"}, {\"external_id\":\"B\",\"at\":5}]")
        assertEquals(1, read.size)
        assertEquals("B", read[0].externalId)
    }

    @Test
    fun theTokenItselfIsNeverKept() {
        val entry = entry(orderId = null, purchaseToken = "secret-purchase-token")
        assertNotNull(entry)
        assertFalse(Purchases.writeOutbox(listOf(entry!!)).contains("secret-purchase-token"))
    }

    private fun entry(
        productId: String? = "premium_yearly",
        orderId: String? = "GPA.1",
        purchaseToken: String? = "token",
        price: Double? = null,
        currency: String? = null,
        trial: Boolean = false,
        auto: Boolean = false,
    ) = Purchases.entry(productId, trial, 1_000L, orderId, purchaseToken, price, currency, auto)
}

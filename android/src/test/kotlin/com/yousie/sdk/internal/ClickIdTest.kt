package com.yousie.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClickIdTest {
    private val id = "AbCdEfGhIjKlMnOpQrSt12"

    @Test
    fun readsTheClickIdOfACreatorLink() {
        assertEquals(id, ClickId.fromReferrer("utm_source=yousie&utm_medium=affiliate&utm_campaign=abcd2345&yclid=$id"))
        assertEquals(id, ClickId.fromReferrer("yclid=$id"))
        assertEquals(id, ClickId.fromReferrer("yclid=$id&utm_source=yousie"))
    }

    @Test
    fun readsAReferrerThatArrivesStillEncodedOnce() {
        assertEquals(id, ClickId.fromReferrer("utm_source%3Dyousie%26utm_medium%3Daffiliate%26yclid%3D$id"))
    }

    @Test
    fun installsWithoutAYousieLinkHaveNone() {
        assertNull(ClickId.fromReferrer(null))
        assertNull(ClickId.fromReferrer(""))
        assertNull(ClickId.fromReferrer("utm_source=google-play&utm_medium=organic"))
        assertNull(ClickId.fromReferrer("gclid=EAIaIQobChMI&utm_source=google"))
        assertNull(ClickId.fromReferrer("utm_source=invite&ref=ABC123"))
    }

    @Test
    fun onlyAParameterNamedExactlyYclidCounts() {
        assertNull(ClickId.fromReferrer("xyclid=$id"))
        assertNull(ClickId.fromReferrer("yclid2=$id"))
        assertNull(ClickId.fromReferrer("YCLID=$id"))
    }

    @Test
    fun aValueThatIsNotAClickIdIsRefused() {
        assertNull(ClickId.fromReferrer("yclid=short"))
        assertNull(ClickId.fromReferrer("yclid=${id}x"))
        assertNull(ClickId.fromReferrer("yclid=AbCdEfGhIjKlMnOpQrSt1-"))
        assertNull(ClickId.fromReferrer("yclid="))
        // A value that needs decoding is not a click id.
        assertNull(ClickId.fromReferrer("yclid=AbCdEfGhIjKlMnOpQrSt%31"))
    }

    @Test
    fun theFirstYclidWins() {
        assertEquals(id, ClickId.fromReferrer("yclid=$id&yclid=ZZZZZZZZZZZZZZZZZZZZZZ"))
        assertNull(ClickId.fromReferrer("yclid=bad&yclid=$id"))
    }

    @Test
    fun neverThrowsOnAMalformedReferrer() {
        assertNull(ClickId.fromReferrer("%"))
        assertNull(ClickId.fromReferrer("%E0%A4%A"))
        assertNull(ClickId.fromReferrer("&&=&=x&yclid"))
        assertEquals(id, ClickId.fromReferrer("%zz=1&yclid=$id"))
    }
}

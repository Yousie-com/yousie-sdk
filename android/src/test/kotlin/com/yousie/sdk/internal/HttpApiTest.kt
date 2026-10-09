package com.yousie.sdk.internal

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

private const val KEY = "ysk_0123456789abcdefghijABCDEFGHIJ01"
private const val CLICK = "AbCdEfGhIjKlMnOpQrSt12"
private const val INSTALL = "5f0c2b9e-6a53-4d0a-9d0b-2f4b7c1e8a11"

class HttpApiTest {
    private val server = TinyServer()
    private val api = HttpApi(server.url + "/", "yousie-sdk-android/test", log = {})

    @After
    fun stop() = server.close()

    private fun purchase(
        productId: String? = "premium_yearly",
        priceMicros: Long? = 29_990_000L,
        currency: String? = "EUR",
        trial: Boolean = true,
    ) = OutboxEntry("GPA.1", productId, priceMicros, currency, trial, at = 1L, auto = false)

    // ─── What is sent ───────────────────────────────────────────────────

    @Test
    fun anInstallReportCarriesItsFiveFieldsAndNothingElse() {
        server.answer(200, """{"attributed":true,"install":"ins_0123456789abcdef"}""")
        api.reportInstall(KEY, CLICK, INSTALL, " 2.14.3+458 ")

        val request = server.requests.single()
        assertEquals("POST /api/v1/installs HTTP/1.1", request.line)
        val body = JSONObject(request.body)
        assertEquals(setOf("sdk_key", "click_id", "install_id", "platform", "app_version"), body.keySet())
        assertEquals(KEY, body.getString("sdk_key"))
        assertEquals(CLICK, body.getString("click_id"))
        assertEquals(INSTALL, body.getString("install_id"))
        assertEquals("android", body.getString("platform"))
        assertEquals("2.14.3+458", body.getString("app_version"))
    }

    @Test
    fun aRequestSaysWhichSdkMadeItAndNothingAboutThePhone() {
        server.answer(200, """{"attributed":false,"reason":"unknown_click"}""")
        api.reportInstall(KEY, CLICK, INSTALL, null)

        val request = server.requests.single()
        assertEquals("yousie-sdk-android/test", request.headers["user-agent"])
        assertTrue(request.headers["content-type"]!!.startsWith("application/json"))
        // The transport's own headers and ours: nothing else, whatever the
        // platform's HTTP stack would add by default.
        val expected = setOf(
            "user-agent", "content-type", "accept", "host", "connection", "content-length",
            "cache-control", "pragma", "accept-encoding",
        )
        assertEquals(emptySet<String>(), request.headers.keys - expected)
        assertFalse(JSONObject(request.body).has("app_version"))
    }

    @Test
    fun anAppVersionTheApiWouldRefuseIsLeftOut() {
        server.answer(200, """{"attributed":false}""")
        api.reportInstall(KEY, CLICK, INSTALL, "x".repeat(41))
        server.answer(200, """{"attributed":false}""")
        api.reportInstall(KEY, CLICK, INSTALL, "version é")

        assertFalse(JSONObject(server.requests[0].body).has("app_version"))
        assertFalse(JSONObject(server.requests[1].body).has("app_version"))
    }

    @Test
    fun aSubscriptionReportCarriesThePurchase() {
        server.answer(200, """{"recorded":true,"conversion":"cnv_0123456789abcdef","status":"pending"}""")
        api.reportSubscription(KEY, INSTALL, purchase())

        val request = server.requests.single()
        assertEquals("POST /api/v1/events HTTP/1.1", request.line)
        val body = JSONObject(request.body)
        assertEquals(
            setOf("sdk_key", "install_id", "event", "external_id", "product_id", "price_micros", "currency", "trial"),
            body.keySet(),
        )
        assertEquals("subscription", body.getString("event"))
        assertEquals("GPA.1", body.getString("external_id"))
        assertEquals("premium_yearly", body.getString("product_id"))
        assertEquals(29_990_000L, body.getLong("price_micros"))
        assertEquals("EUR", body.getString("currency"))
        assertTrue(body.getBoolean("trial"))
    }

    @Test
    fun aSubscriptionWithoutAPriceSendsNeitherPriceNorCurrency() {
        server.answer(200, """{"recorded":true}""")
        api.reportSubscription(KEY, INSTALL, purchase(productId = null, priceMicros = null, currency = null, trial = false))

        val body = JSONObject(server.requests.single().body)
        assertEquals(setOf("sdk_key", "install_id", "event", "external_id", "trial"), body.keySet())
        assertFalse(body.getBoolean("trial"))
    }

    // ─── What comes back ────────────────────────────────────────────────

    @Test
    fun anAttributedInstall() {
        server.answer(200, """{"attributed":true,"install":"ins_0123456789abcdef"}""")
        val answer = api.reportInstall(KEY, CLICK, INSTALL, null)
        assertEquals(Delivery.ANSWERED, answer.delivery)
        assertTrue(answer.attributed)
        assertEquals("ins_0123456789abcdef", answer.installRef)
    }

    @Test
    fun anInstallYousieWillNotBook() {
        server.answer(200, """{"attributed":false,"reason":"expired"}""")
        val answer = api.reportInstall(KEY, CLICK, INSTALL, null)
        assertEquals(Delivery.ANSWERED, answer.delivery)
        assertFalse(answer.attributed)
        assertEquals("expired", answer.reason)
    }

    @Test
    fun anAnswerThatNamesNoInstallIsStillAttributed() {
        server.answer(201, """{"attributed":true}""")
        val answer = api.reportInstall(KEY, CLICK, INSTALL, null)
        assertTrue(answer.attributed)
        assertEquals("", answer.installRef)
    }

    @Test
    fun anythingElseIsNotAnAnswer() {
        val notAnswers = listOf(
            422 to """{"error":"invalid","field":"click_id"}""",
            500 to "<html>Bad gateway</html>",
            200 to "<html>a proxy's page</html>",
            200 to """["attributed"]""",
            200 to """{"attributed":"yes"}""",
            200 to "",
            302 to "",
        )
        for ((status, body) in notAnswers) {
            server.answer(status, body)
            val answer = api.reportInstall(KEY, CLICK, INSTALL, null)
            assertEquals("$status $body", Delivery.REFUSED, answer.delivery)
            assertEquals(status, answer.status)
            assertFalse(answer.attributed)
        }
    }

    @Test
    fun aRecordedAndANotRecordedEvent() {
        server.answer(200, """{"recorded":true,"conversion":"cnv_1","status":"pending"}""")
        val recorded = api.reportSubscription(KEY, INSTALL, purchase())
        assertEquals(Delivery.ANSWERED, recorded.delivery)
        assertTrue(recorded.recorded)

        server.answer(200, """{"recorded":false,"reason":"duplicate"}""")
        val duplicate = api.reportSubscription(KEY, INSTALL, purchase())
        assertEquals(Delivery.ANSWERED, duplicate.delivery)
        assertFalse(duplicate.recorded)
        assertEquals("duplicate", duplicate.reason)

        server.answer(503, "")
        assertEquals(Delivery.REFUSED, api.reportSubscription(KEY, INSTALL, purchase()).delivery)
    }

    @Test
    fun aBodyThatOutgrowsTheCapIsNoAnswer() {
        server.answer(200, """{"attributed":true,"pad":"${"x".repeat(70_000)}"}""")
        val answer = api.reportInstall(KEY, CLICK, INSTALL, null)
        assertEquals(Delivery.UNREACHABLE, answer.delivery)
        assertNull(answer.status)
    }

    @Test
    fun aServerThatCannotBeReachedIsNoAnswer() {
        server.close()
        assertEquals(Delivery.UNREACHABLE, api.reportInstall(KEY, CLICK, INSTALL, null).delivery)
        assertEquals(Delivery.UNREACHABLE, api.reportSubscription(KEY, INSTALL, purchase()).delivery)
    }

    @Test
    fun theDefaultOriginIsYousie() {
        assertEquals("https://yousie.com", HttpApi.DEFAULT_BASE)
    }
}

/** One canned answer per request, on a local port. */
private class TinyServer {
    class Request(val line: String, val headers: Map<String, String>, val body: String)

    private val socket = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
    private val answers = java.util.concurrent.LinkedBlockingQueue<Pair<Int, String>>()
    val requests = CopyOnWriteArrayList<Request>()
    val url = "http://127.0.0.1:${socket.localPort}"

    private val thread = Thread {
        while (!socket.isClosed) {
            try {
                socket.accept().use(::serve)
            } catch (e: Exception) {
                // Closed, or a client that went away.
            }
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun answer(status: Int, body: String) {
        answers.put(status to body)
    }

    fun close() {
        socket.close()
        thread.join(2_000)
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val head = ByteArrayOutputStream()
        // Up to the blank line that ends the headers.
        while (!head.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) return
            head.write(byte)
        }
        val lines = head.toString("ISO-8859-1").trim().split("\r\n")
        val headers = lines.drop(1).associate {
            it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) break
            read += count
        }
        requests += Request(lines[0], headers, String(body, Charsets.UTF_8))

        val (status, text) = answers.take()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = client.getOutputStream()
        out.write(
            (
                "HTTP/1.1 $status X\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.ISO_8859_1),
        )
        out.write(bytes)
        out.flush()
    }
}

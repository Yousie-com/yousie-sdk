package com.yousie.sdk.internal

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** How a request to Yousie ended, before what the answer says. */
internal enum class Delivery {
    /**
     * A success carrying the JSON object the route promises. The only thing
     * that counts as Yousie's answer: it settles the question that was asked.
     */
    ANSWERED,

    /**
     * Yousie's side spoke, but not that: a 4xx, a 5xx, a proxy's error page.
     * Nothing is settled; the same request may be answered properly later.
     */
    REFUSED,

    /**
     * No response that could be read: offline, a timeout, TLS, or a body
     * this client cut short itself. Nothing is settled, and the next request
     * would fare no better.
     */
    UNREACHABLE,
}

/** The answer of `POST /api/v1/installs`. */
internal class InstallAnswer private constructor(
    val delivery: Delivery,
    /** True only for an answered report that books the install. */
    val attributed: Boolean = false,
    /** Yousie's own name for the booked install (`ins_…`). */
    val installRef: String? = null,
    /** Why an answered install was not booked (`unknown_click`, `expired`, …). */
    val reason: String? = null,
    val status: Int? = null,
) {
    companion object {
        fun attributed(installRef: String, status: Int = 200) =
            InstallAnswer(Delivery.ANSWERED, attributed = true, installRef = installRef, status = status)

        fun notAttributed(reason: String?, status: Int = 200) =
            InstallAnswer(Delivery.ANSWERED, reason = reason, status = status)

        fun refused(status: Int) = InstallAnswer(Delivery.REFUSED, status = status)

        fun unreachable() = InstallAnswer(Delivery.UNREACHABLE)
    }
}

/** The answer of `POST /api/v1/events`. */
internal class EventAnswer private constructor(
    val delivery: Delivery,
    /** Whether an answered event was booked as a conversion. */
    val recorded: Boolean = false,
    /** Why it was not (`not_attributed`, `duplicate`, `unsupported_event`). */
    val reason: String? = null,
    val status: Int? = null,
) {
    companion object {
        fun answered(recorded: Boolean, reason: String? = null, status: Int = 200) =
            EventAnswer(Delivery.ANSWERED, recorded = recorded, reason = reason, status = status)

        fun refused(status: Int) = EventAnswer(Delivery.REFUSED, status = status)

        fun unreachable() = EventAnswer(Delivery.UNREACHABLE)
    }
}

/** Yousie's two public tracking routes. */
internal interface Api {
    fun reportInstall(sdkKey: String, clickId: String, installId: String, appVersion: String?): InstallAnswer

    fun reportSubscription(sdkKey: String, installId: String, purchase: OutboxEntry): EventAnswer
}

/**
 * The two routes over HTTPS, and nothing else: no state, no consent. The
 * engine decides when to call and what an answer means.
 *
 * A request carries its JSON body and a User-Agent that names the SDK. The
 * platform's default User-Agent is replaced on purpose: Android's states the
 * phone model and system build, and none of that is Yousie's to know.
 */
internal class HttpApi(
    baseUrl: String?,
    private val userAgent: String,
    private val log: (String) -> Unit,
) : Api {
    private val base: String = baseUrl?.trim()?.trimEnd('/').orEmpty().ifEmpty { DEFAULT_BASE }

    override fun reportInstall(sdkKey: String, clickId: String, installId: String, appVersion: String?): InstallAnswer {
        val body = JSONObject()
        body.put("sdk_key", sdkKey)
        body.put("click_id", clickId)
        body.put("install_id", installId)
        body.put("platform", PLATFORM)
        val version = appVersion?.trim().orEmpty()
        if (APP_VERSION.matches(version)) body.put("app_version", version)

        val response = post(INSTALLS_PATH, body) ?: return InstallAnswer.unreachable()
        val json = (if (response.isSuccess) response.body else null)
            ?: return InstallAnswer.refused(response.status)
        val attributed = json.opt("attributed") as? Boolean ?: return InstallAnswer.refused(response.status)
        if (!attributed) return InstallAnswer.notAttributed(json.opt("reason") as? String, response.status)
        val ref = json.opt("install") as? String
        val known = ref != null && ref.isNotEmpty() && ref.length <= 64
        return InstallAnswer.attributed(if (known) ref!! else "", response.status)
    }

    override fun reportSubscription(sdkKey: String, installId: String, purchase: OutboxEntry): EventAnswer {
        val body = JSONObject()
        body.put("sdk_key", sdkKey)
        body.put("install_id", installId)
        body.put("event", "subscription")
        body.put("external_id", purchase.externalId)
        if (purchase.productId != null) body.put("product_id", purchase.productId)
        if (purchase.priceMicros != null && purchase.currency != null) {
            body.put("price_micros", purchase.priceMicros)
            body.put("currency", purchase.currency)
        }
        body.put("trial", purchase.trial)

        val response = post(EVENTS_PATH, body) ?: return EventAnswer.unreachable()
        val json = (if (response.isSuccess) response.body else null)
            ?: return EventAnswer.refused(response.status)
        val recorded = json.opt("recorded") as? Boolean ?: return EventAnswer.refused(response.status)
        return EventAnswer.answered(recorded, json.opt("reason") as? String, response.status)
    }

    private class Response(val status: Int, val body: JSONObject?) {
        val isSuccess: Boolean get() = status in 200..299
    }

    /** Null when no response could be read. Every status is a response. */
    private fun post(path: String, body: JSONObject): Response? {
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        try {
            connection = URL(base + path).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.useCaches = false
            // A redirect is not an answer, and is never followed elsewhere.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            val text = if (stream == null) "" else stream.use { read(it, started) }
            if (text == null) {
                log("$path failed: the response was too large or too slow")
                return null
            }
            return Response(status, parse(text))
        } catch (e: Exception) {
            log("$path failed: ${e.javaClass.simpleName}")
            return null
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                // Nothing to do about a connection that will not close.
            }
        }
    }

    /** The body as text; null once it outgrows the cap or the deadline. */
    private fun read(stream: InputStream, started: Long): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            if (out.size() > MAX_RESPONSE_BYTES) return null
            if (System.nanoTime() - started > DEADLINE_NS) return null
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun parse(text: String): JSONObject? =
        try {
            JSONTokener(text).nextValue() as? JSONObject
        } catch (e: JSONException) {
            null
        }

    companion object {
        const val DEFAULT_BASE = "https://yousie.com"
        const val INSTALLS_PATH = "/api/v1/installs"
        const val EVENTS_PATH = "/api/v1/events"
        const val PLATFORM = "android"

        private const val TIMEOUT_MS = 10_000
        private const val DEADLINE_NS = 20_000_000_000L
        private const val MAX_RESPONSE_BYTES = 64 * 1024

        // At most 40 plain characters, as the API accepts it.
        private val APP_VERSION = Regex("^[\\x20-\\x7E]{1,40}$")
    }
}

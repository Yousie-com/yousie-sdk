package com.yousie.sdk.internal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

/** One subscription the app owns, as Play Billing describes it. */
internal class StorePurchase(
    val productId: String,
    val orderId: String?,
    val purchaseToken: String?,
    /** Epoch ms, on Google's clock. */
    val purchaseTime: Long,
    /** False while the payment is still pending. */
    val purchased: Boolean,
)

/** The subscriptions the app owns right now. */
internal interface PurchaseSource {
    /** Null when Play Billing could not be asked; ask again later. */
    fun subscriptions(): List<StorePurchase>?
}

/**
 * One subscription purchase waiting to be delivered to Yousie: the event's
 * own fields, and [at] for the give-up date. Never the purchase token.
 */
internal class OutboxEntry(
    val externalId: String,
    val productId: String?,
    val priceMicros: Long?,
    val currency: String?,
    val trial: Boolean,
    /** Epoch ms on the device clock: when the purchase was noted. */
    var at: Long,
    /** Found by automatic tracking, so without price and trial. */
    val auto: Boolean,
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("external_id", externalId)
        if (productId != null) json.put("product_id", productId)
        if (priceMicros != null && currency != null) {
            json.put("price_micros", priceMicros)
            json.put("currency", currency)
        }
        json.put("trial", trial)
        json.put("at", at)
        if (auto) json.put("auto", true)
        return json
    }

    companion object {
        /** Null for an entry that names no purchase or carries no date. */
        fun fromJson(json: JSONObject): OutboxEntry? {
            val externalId = json.opt("external_id") as? String
            if (externalId.isNullOrEmpty()) return null
            val at = (json.opt("at") as? Number)?.toLong() ?: return null
            val priceMicros = (json.opt("price_micros") as? Number)?.toLong()
            val currency = json.opt("currency") as? String
            val priced = priceMicros != null && currency != null
            return OutboxEntry(
                externalId = externalId,
                productId = json.opt("product_id") as? String,
                priceMicros = if (priced) priceMicros else null,
                currency = if (priced) currency else null,
                trial = json.opt("trial") == true,
                at = at,
                auto = json.opt("auto") == true,
            )
        }
    }
}

internal object Purchases {
    // 1 to 128 visible ASCII characters, no space. Yousie's API holds the
    // other copy of this rule: spaces are refused so two spellings of one
    // id cannot slip past its uniqueness check.
    private val VISIBLE_ASCII = Regex("^[\\x21-\\x7E]{1,128}$")
    private val CURRENCY = Regex("^[A-Z]{3}$")

    /**
     * What Yousie dedupes a purchase on: the store's order id, else `tok:`
     * and a hash prefix of the purchase token; null when the store gave
     * neither. An order id Yousie would refuse is not sent as it stands (a
     * refusal is no answer, so the purchase would be retried for a week and
     * never booked): the token names it instead.
     */
    fun externalIdFor(orderId: String?, purchaseToken: String?): String? {
        val order = orderId?.trim().orEmpty()
        if (VISIBLE_ASCII.matches(order)) return order
        if (purchaseToken.isNullOrEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(purchaseToken.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return "tok:" + hex.substring(0, 32)
    }

    /**
     * The outbox entry of one purchase; null when nothing names it. The
     * price travels only with a currency code, and only when it is a price
     * (finite, not negative, small enough to stay an exact whole number of
     * micros).
     */
    fun entry(
        productId: String?,
        trial: Boolean,
        at: Long,
        orderId: String?,
        purchaseToken: String?,
        price: Double?,
        currency: String?,
        auto: Boolean,
    ): OutboxEntry? {
        val externalId = externalIdFor(orderId, purchaseToken) ?: return null
        val product = productId?.trim().orEmpty()
        val code = currency?.trim().orEmpty().uppercase()
        val priced = price != null &&
            !price.isNaN() && !price.isInfinite() &&
            price >= 0 && price < 1e9 &&
            CURRENCY.matches(code)
        return OutboxEntry(
            externalId = externalId,
            productId = if (VISIBLE_ASCII.matches(product)) product else null,
            priceMicros = if (priced) Math.round(price!! * 1e6) else null,
            currency = if (priced) code else null,
            trial = trial,
            at = at,
            auto = auto,
        )
    }

    fun readOutbox(raw: String?): MutableList<OutboxEntry> {
        val entries = ArrayList<OutboxEntry>()
        if (raw.isNullOrEmpty()) return entries
        try {
            val list = JSONArray(raw)
            for (i in 0 until list.length()) {
                val item = list.opt(i) as? JSONObject ?: continue
                OutboxEntry.fromJson(item)?.let(entries::add)
            }
        } catch (e: JSONException) {
            entries.clear() // A corrupt value: start clean.
        }
        return entries
    }

    fun writeOutbox(entries: List<OutboxEntry>): String {
        val list = JSONArray()
        for (entry in entries) list.put(entry.toJson())
        return list.toString()
    }

    fun readIds(raw: String?): MutableList<String> {
        val ids = ArrayList<String>()
        if (raw.isNullOrEmpty()) return ids
        try {
            val list = JSONArray(raw)
            for (i in 0 until list.length()) (list.opt(i) as? String)?.let(ids::add)
        } catch (e: JSONException) {
            ids.clear()
        }
        return ids
    }

    fun writeIds(ids: List<String>): String {
        val list = JSONArray()
        for (id in ids) list.put(id)
        return list.toString()
    }

    private const val HEX = "0123456789abcdef"
}

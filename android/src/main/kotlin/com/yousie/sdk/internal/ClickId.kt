package com.yousie.sdk.internal

import java.net.URLDecoder

/**
 * The Yousie click id in a Play install referrer string.
 *
 * A creator's link (`https://yousie.com/c/<code>`) sends Android to Play with
 * `utm_source=yousie&utm_medium=affiliate&utm_campaign=<code>&yclid=<id>`, and
 * Play hands that string back to the app after the install.
 */
internal object ClickId {
    /** The referrer parameter that carries the click id. */
    const val PARAM = "yclid"

    // 22 base62 characters, nothing else. Yousie's API holds the other copy
    // of this rule and refuses anything else, so a value that does not pass
    // here is not worth a request.
    private val SHAPE = Regex("^[A-Za-z0-9]{22}$")

    /**
     * The click id [referrer] carries, or null when the install did not come
     * through a Yousie link.
     *
     * The string normally arrives decoded, but a link that was encoded twice
     * on its way arrives still encoded once, so both are read. Only a
     * parameter named exactly `yclid` counts, the first one wins, and its
     * value is taken as it stands. Never throws, whatever the link said.
     */
    fun fromReferrer(referrer: String?): String? {
        if (referrer.isNullOrEmpty()) return null
        val direct = param(referrer)
        if (direct != null) return shaped(direct)
        val decoded = decode(referrer)
        if (decoded == null || decoded == referrer) return null
        val nested = param(decoded) ?: return null
        return shaped(nested)
    }

    private fun shaped(value: String): String? = if (SHAPE.matches(value)) value else null

    // Split by hand: a referrer is whatever the link said, and a strict
    // query parser throws on a malformed escape anywhere in the string.
    private fun param(query: String): String? {
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq).trim() != PARAM) continue
            return pair.substring(eq + 1)
        }
        return null
    }

    private fun decode(value: String): String? =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: Exception) {
            null
        }
}

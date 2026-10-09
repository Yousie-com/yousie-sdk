package com.yousie.sdk.internal

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The Play install referrer of this install, read through Google's Install
 * Referrer library. Called on the SDK's worker thread, once per install.
 */
internal class PlayReferrerSource(private val context: Context) : ReferrerSource {

    override fun read(): ReferrerRead {
        val client = try {
            InstallReferrerClient.newBuilder(context).build()
        } catch (t: Throwable) {
            // No client at all: asking again will not change that.
            return ReferrerRead.Value(null, null)
        }
        val connected = CountDownLatch(1)
        val response = AtomicReference<Int?>(null)
        val listener = object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                response.compareAndSet(null, responseCode)
                connected.countDown()
            }

            override fun onInstallReferrerServiceDisconnected() {
                response.compareAndSet(null, InstallReferrerResponse.SERVICE_DISCONNECTED)
                connected.countDown()
            }
        }
        try {
            client.startConnection(listener)
            // The callback comes on the main thread, which a cold start
            // keeps busy: a Play Store that never calls back must not leave
            // the worker waiting for ever.
            if (!connected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) return ReferrerRead.Unavailable
            return when (response.get()) {
                // getInstallReferrer is a binder call, made here on the
                // worker thread and never on the main one.
                InstallReferrerResponse.OK -> value(client.installReferrer)
                InstallReferrerResponse.SERVICE_UNAVAILABLE,
                InstallReferrerResponse.SERVICE_DISCONNECTED,
                -> ReferrerRead.Unavailable
                // FEATURE_NOT_SUPPORTED (no Play Store, or a very old one),
                // DEVELOPER_ERROR, PERMISSION_ERROR: asking again will not
                // change them, so no referrer.
                else -> ReferrerRead.Value(null, null)
            }
        } catch (t: Throwable) {
            return ReferrerRead.Unavailable
        } finally {
            try {
                client.endConnection()
            } catch (t: Throwable) {
                // Already gone.
            }
        }
    }

    private fun value(details: ReferrerDetails): ReferrerRead.Value {
        // Google's clock first; the phone's own when Play is too old to say.
        val server = details.installBeginTimestampServerSeconds
        val local = details.installBeginTimestampSeconds
        val beginSeconds = if (server > 0) server else local
        return ReferrerRead.Value(
            referrer = details.installReferrer,
            installBeginMs = if (beginSeconds > 0) beginSeconds * 1000 else null,
        )
    }

    private companion object {
        const val TIMEOUT_MS = 6_000L
    }
}

/** A referrer handed to a debuggable build (YousieOptions.debugReferrer). */
internal class FixedReferrerSource(private val referrer: String) : ReferrerSource {
    override fun read(): ReferrerRead = ReferrerRead.Value(referrer, null)
}

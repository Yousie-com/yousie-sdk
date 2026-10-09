package com.yousie.flutter

import android.content.Context
import com.yousie.sdk.Yousie
import com.yousie.sdk.YousieConsent
import com.yousie.sdk.YousieOptions
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * The Flutter side of the Yousie SDK: three calls, each handed to the
 * Android SDK as it is. All the work happens there, off the main thread.
 */
class YousieFlutterPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private var channel: MethodChannel? = null
    private var context: Context? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL).also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        context = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "init" -> init(call)
                "setConsent" -> Yousie.setConsent(consent(call.argument<String>("consent")))
                "trackSubscription" -> Yousie.trackSubscription(
                    productId = call.argument<String>("productId").orEmpty(),
                    orderId = call.argument<String>("orderId"),
                    purchaseToken = call.argument<String>("purchaseToken"),
                    price = call.argument<Number>("price")?.toDouble(),
                    currency = call.argument<String>("currency"),
                    trial = call.argument<Boolean>("trial") ?: false,
                )
                else -> {
                    result.notImplemented()
                    return
                }
            }
        } catch (t: Throwable) {
            // Nothing here may reach the app: the Android SDK already
            // swallows its own failures, and a malformed call is dropped.
        }
        result.success(null)
    }

    private fun init(call: MethodCall) {
        val app = context ?: return
        Yousie.wrapper = "flutter"
        Yousie.init(
            app,
            call.argument<String>("sdkKey").orEmpty(),
            consent(call.argument<String>("consent")),
            YousieOptions(
                autoTrackPurchases = call.argument<Boolean>("autoTrackPurchases") ?: true,
                logging = call.argument<Boolean>("logging") ?: false,
                debugReferrer = call.argument<String>("debugReferrer"),
                debugReset = call.argument<Boolean>("debugReset") ?: false,
                debugApiUrl = call.argument<String>("debugApiUrl"),
            ),
        )
    }

    // A value this version does not know is an answer nobody gave.
    private fun consent(name: String?): YousieConsent =
        when (name) {
            "granted" -> YousieConsent.GRANTED
            "denied" -> YousieConsent.DENIED
            else -> YousieConsent.UNKNOWN
        }

    private companion object {
        const val CHANNEL = "com.yousie.sdk/flutter"
    }
}

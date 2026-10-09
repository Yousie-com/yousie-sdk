/// Yousie SDK for Flutter.
///
/// Tells Yousie that this install came from a creator's link, and later that
/// it bought a subscription, so the creator is paid for both.
///
/// Android only. On every other platform each call returns at once and does
/// nothing, so the same code runs everywhere.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Whether the user allows this install to be reported to Yousie.
///
/// Yousie is a paid-attribution network, so what the SDK sends is advertising
/// data. Where the law asks for consent (the EEA, the UK, Switzerland), state
/// the user's answer here. Where it does not, pass [granted].
enum YousieConsent {
  /// The install may be reported, and its subscription after it.
  granted,

  /// The user said no. The click id is deleted and nothing is sent. This is
  /// final for the install: a later [granted] has nothing left to report.
  denied,

  /// Nobody has answered yet (the consent form has not been shown or is
  /// still open). The click id waits on the phone, for a week at most, and
  /// nothing is sent until [Yousie.setConsent] states an answer.
  unknown,
}

/// The three calls of the SDK. None of them throws, and none needs to be
/// awaited: the work happens on a background thread of the Android SDK.
class Yousie {
  Yousie._();

  static const MethodChannel _channel = MethodChannel('com.yousie.sdk/flutter');

  // defaultTargetPlatform rather than dart:io's Platform, so this file also
  // compiles for the web and a test can stand on either side.
  static bool get _supported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  /// Starts the SDK. Call it once, as early as you can (in `main`, after
  /// `WidgetsFlutterBinding.ensureInitialized()`).
  ///
  /// On the first launch it reads the Play install referrer; when the install
  /// came through a creator's link and [consent] allows it, the install is
  /// reported. A second call only updates the consent.
  ///
  /// [sdkKey] is the app's public SDK key from the Yousie dashboard (`ysk_`
  /// and 32 characters). It is not a secret.
  ///
  /// [autoTrackPurchases] reads the app's own Google Play subscriptions and
  /// reports a new one without any call from the app: only on installs that
  /// came through a creator's link, and only when the app has Play Billing
  /// Library 7 or newer. An automatic report has no price and no trial flag;
  /// call [trackSubscription] to send those.
  ///
  /// [logging] prints what the SDK does to logcat, under the tag `Yousie`.
  ///
  /// The three `debug…` settings only work in a debug build:
  /// [debugReferrer] is used instead of the Play install referrer
  /// (`utm_source=yousie&utm_medium=affiliate&utm_campaign=<code>&yclid=<click id>`),
  /// [debugReset] forgets everything the SDK saved each time it starts, and
  /// [debugApiUrl] sends the reports to another origin than
  /// `https://yousie.com`.
  static Future<void> init({
    required String sdkKey,
    required YousieConsent consent,
    bool autoTrackPurchases = true,
    bool logging = false,
    String? debugReferrer,
    bool debugReset = false,
    String? debugApiUrl,
  }) => _call('init', {
    'sdkKey': sdkKey,
    'consent': consent.name,
    'autoTrackPurchases': autoTrackPurchases,
    'logging': logging,
    'debugReferrer': debugReferrer,
    'debugReset': debugReset,
    'debugApiUrl': debugApiUrl,
  });

  /// States the user's answer to the consent question, whenever it is known
  /// or changes. Stating the same answer again does nothing.
  static Future<void> setConsent(YousieConsent consent) =>
      _call('setConsent', {'consent': consent.name});

  /// Reports a subscription the user has just bought. Call it for a fresh
  /// purchase only, not when a purchase is restored.
  ///
  /// Nothing is stored or sent unless the install came through a creator's
  /// link. Reporting the same purchase twice is safe: Yousie keeps one.
  ///
  /// [orderId] is the store's order id (`PurchaseDetails.purchaseID`).
  /// [purchaseToken] is only used when there is no order id, and then only as
  /// a hash: the token itself is never stored or sent. [price] is the plan's
  /// price in [currency] (for a trial, what the trial becomes) and is sent
  /// only together with a three-letter currency code. [trial] says whether
  /// the purchase starts a free trial.
  static Future<void> trackSubscription({
    required String productId,
    String? orderId,
    String? purchaseToken,
    double? price,
    String? currency,
    bool trial = false,
  }) => _call('trackSubscription', {
    'productId': productId,
    'orderId': orderId,
    'purchaseToken': purchaseToken,
    'price': price,
    'currency': currency,
    'trial': trial,
  });

  static Future<void> _call(String method, Map<String, Object?> arguments) async {
    if (!_supported) return;
    try {
      await _channel.invokeMethod<void>(method, arguments);
    } catch (e) {
      // Attribution must never break the app that carries it.
      if (kDebugMode) debugPrint('[Yousie] $method: $e');
    }
  }
}

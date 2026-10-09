import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yousie/yousie.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('com.yousie.sdk/flutter');
  final calls = <MethodCall>[];
  Object? Function(MethodCall call)? answer;

  setUp(() {
    calls.clear();
    answer = null;
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return answer?.call(call);
        });
  });

  tearDown(() {
    debugDefaultTargetPlatformOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('init hands the key, the consent and the defaults to Android', () async {
    await Yousie.init(
      sdkKey: 'ysk_0123456789abcdefghijABCDEFGHIJ01',
      consent: YousieConsent.unknown,
    );

    expect(calls.single.method, 'init');
    expect(calls.single.arguments, {
      'sdkKey': 'ysk_0123456789abcdefghijABCDEFGHIJ01',
      'consent': 'unknown',
      'autoTrackPurchases': true,
      'logging': false,
      'debugReferrer': null,
      'debugReset': false,
      'debugApiUrl': null,
    });
  });

  test('init hands over every option', () async {
    await Yousie.init(
      sdkKey: 'ysk_x',
      consent: YousieConsent.granted,
      autoTrackPurchases: false,
      logging: true,
      debugReferrer: 'utm_source=yousie&yclid=AbCdEfGhIjKlMnOpQrSt12',
      debugReset: true,
      debugApiUrl: 'http://10.0.2.2:8797',
    );

    expect(calls.single.arguments, {
      'sdkKey': 'ysk_x',
      'consent': 'granted',
      'autoTrackPurchases': false,
      'logging': true,
      'debugReferrer': 'utm_source=yousie&yclid=AbCdEfGhIjKlMnOpQrSt12',
      'debugReset': true,
      'debugApiUrl': 'http://10.0.2.2:8797',
    });
  });

  test('setConsent states each of the three answers by name', () async {
    await Yousie.setConsent(YousieConsent.granted);
    await Yousie.setConsent(YousieConsent.denied);
    await Yousie.setConsent(YousieConsent.unknown);

    expect(calls.map((c) => c.method).toSet(), {'setConsent'});
    expect(calls.map((c) => (c.arguments as Map)['consent']), [
      'granted',
      'denied',
      'unknown',
    ]);
  });

  test('trackSubscription hands over the purchase', () async {
    await Yousie.trackSubscription(
      productId: 'premium_yearly',
      orderId: 'GPA.3345-1234-5678-90123',
      purchaseToken: 'token',
      price: 29.99,
      currency: 'EUR',
      trial: true,
    );

    expect(calls.single.method, 'trackSubscription');
    expect(calls.single.arguments, {
      'productId': 'premium_yearly',
      'orderId': 'GPA.3345-1234-5678-90123',
      'purchaseToken': 'token',
      'price': 29.99,
      'currency': 'EUR',
      'trial': true,
    });
  });

  test('trackSubscription needs only the product', () async {
    await Yousie.trackSubscription(productId: 'premium_monthly');

    expect(calls.single.arguments, {
      'productId': 'premium_monthly',
      'orderId': null,
      'purchaseToken': null,
      'price': null,
      'currency': null,
      'trial': false,
    });
  });

  test('on iOS every call returns without reaching the platform', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;

    await Yousie.init(sdkKey: 'ysk_x', consent: YousieConsent.granted);
    await Yousie.setConsent(YousieConsent.denied);
    await Yousie.trackSubscription(productId: 'premium_yearly');

    expect(calls, isEmpty);
  });

  test('a platform error never reaches the app', () async {
    answer = (_) => throw PlatformException(code: 'boom');

    await Yousie.init(sdkKey: 'ysk_x', consent: YousieConsent.granted);
    await Yousie.setConsent(YousieConsent.granted);
    await Yousie.trackSubscription(productId: 'premium_yearly');

    expect(calls, hasLength(3));
  });

  test('a missing plugin never reaches the app', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);

    await Yousie.init(sdkKey: 'ysk_x', consent: YousieConsent.granted);
    await Yousie.trackSubscription(productId: 'premium_yearly');
  });
}

# Yousie SDK for Flutter

Reports installs and subscriptions that came through a creator's [Yousie](https://yousie.com) link.

Android only. On iOS and every other platform each call returns at once and does nothing, so the same code runs everywhere.

Full guide: **https://yousie.com/developers**

## Install

Flutter 3.47 or newer, Android 7.0 (API 24) or newer.

```yaml
dependencies:
  yousie:
    git:
      url: https://github.com/Yousie-com/yousie-sdk.git
      path: flutter
      ref: 1.0.0
```

Nothing to add in your Android folder.

## Use

```dart
import 'package:yousie/yousie.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  Yousie.init(sdkKey: 'ysk_your_sdk_key', consent: YousieConsent.granted);
  runApp(const MyApp());
}
```

With a consent form, start with `YousieConsent.unknown` and state the answer when you have it:

```dart
Yousie.setConsent(YousieConsent.granted); // or YousieConsent.denied
```

Subscriptions are found automatically when your app has Play Billing Library 7 or newer (the `in_app_purchase` plugin brings it). To send the price and the trial flag, report the purchase yourself when it completes:

```dart
Yousie.trackSubscription(
  productId: purchase.productID,
  orderId: purchase.purchaseID,
  purchaseToken: purchase.verificationData.serverVerificationData,
  price: 29.99,
  currency: 'EUR',
  trial: true,
);
```

## Test

```dart
Yousie.init(
  sdkKey: 'ysk_your_sdk_key',
  consent: YousieConsent.granted,
  logging: true,
  debugReferrer: 'utm_source=yousie&utm_medium=affiliate&utm_campaign=<code>&yclid=<click id>',
  debugReset: true,
);
```

Then `adb logcat -s Yousie`. The `debug…` options only work in a debug build.

Consent, options, what leaves the phone and how the SDK behaves are described in the [repository README](https://github.com/Yousie-com/yousie-sdk#readme). The plugin is a thin layer over the Android library in the same repository.

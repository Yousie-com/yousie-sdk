# Yousie SDK

The Yousie SDK tells [Yousie](https://yousie.com) two things about your app:

1. **An install came through a creator's link.** On the first launch the SDK reads the Google Play install referrer. If it carries a Yousie click id, the install is reported once.
2. **That install bought a subscription.** The purchase is reported and booked as pending. You confirm it from your server or in your dashboard.

An install that did not come through a Yousie link costs one referrer read on the first launch and nothing after that: no request, no billing connection.

Full guide: **https://yousie.com/developers**

| Package | Folder | For |
|---|---|---|
| Android library | [`android/`](android) | Kotlin and Java apps |
| Flutter plugin | [`flutter/`](flutter) | Flutter apps (wraps the Android library) |

Android only for now. On iOS the Flutter calls do nothing.

## Requirements

- Android 5.0 (API 21) or newer. The Flutter plugin needs Android 7.0 (API 24) and Flutter 3.47 or newer.
- Your app's SDK key (`ysk_…`), from the Yousie dashboard under My Apps. It is public by design: it ships inside your app.
- For automatic purchase tracking: Google Play Billing Library 7 or newer in your app.

## Android

`settings.gradle.kts`

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`app/build.gradle.kts`

```kotlin
dependencies {
    implementation("com.github.yojimbo45:yousie-sdk:1.0.0")
}
```

Start it once, in `Application.onCreate`:

```kotlin
import com.yousie.sdk.Yousie
import com.yousie.sdk.YousieConsent

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Yousie.init(this, "ysk_your_sdk_key", YousieConsent.GRANTED)
    }
}
```

## Flutter

`pubspec.yaml`

```yaml
dependencies:
  yousie:
    git:
      url: https://github.com/yojimbo45/yousie-sdk.git
      path: flutter
      ref: v1.0.0
```

```dart
import 'package:yousie/yousie.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  Yousie.init(sdkKey: 'ysk_your_sdk_key', consent: YousieConsent.granted);
  runApp(const MyApp());
}
```

Nothing to add in your Android folder.

## Consent

What the SDK sends is advertising data. Where the law asks for consent (the EEA, the UK, Switzerland), state the user's answer. Where it does not, pass `GRANTED`.

| Value | What the SDK does |
|---|---|
| `GRANTED` | Reports the install, and its subscription after it. |
| `UNKNOWN` | Keeps the click id on the phone, for a week at most. Sends nothing. |
| `DENIED` | Deletes the click id. Sends nothing. A later `GRANTED` has nothing left to report for this install. |

An app with a consent form starts with `UNKNOWN` and states the answer when it has one:

```kotlin
Yousie.init(this, "ysk_your_sdk_key", YousieConsent.UNKNOWN)
// later
Yousie.setConsent(YousieConsent.GRANTED) // or YousieConsent.DENIED
```

```dart
Yousie.init(sdkKey: 'ysk_your_sdk_key', consent: YousieConsent.unknown);
// later
Yousie.setConsent(YousieConsent.granted); // or YousieConsent.denied
```

## Subscriptions

**Automatic (default).** On an install that came through a creator's link, the SDK reads your app's own Google Play subscriptions when the app comes to the foreground, and reports a new one. It needs Play Billing Library 7 or newer in your app. An automatic report carries the product id and the order id. It does not carry the price or whether the purchase is a trial, because Play Billing does not say.

**Yourself.** To send the price and the trial flag, or if your app has an older Billing Library, report the purchase when it completes:

```kotlin
Yousie.trackSubscription(
    productId = "premium_yearly",
    orderId = purchase.orderId,
    purchaseToken = purchase.purchaseToken,
    price = 29.99,
    currency = "EUR",
    trial = true,
)
```

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

Call it for a fresh purchase, not for a restored one. Reporting the same purchase twice is safe: Yousie keeps one. You can use both ways together: your own report replaces the automatic one while it has not been sent yet.

Yousie books one subscription per install, as **pending**. It becomes earnings for the creator when you confirm it: by hand in your campaign, or from your server with `POST https://yousie.com/api/v1/postback` and your postback secret. The SDK never holds that secret.

## Options

| Option | Default | What it does |
|---|---|---|
| `autoTrackPurchases` | `true` | `false`: only `trackSubscription` reports purchases. |
| `logging` | `false` | Prints what the SDK does to logcat, under the tag `Yousie`. |
| `debugReferrer` | none | Debuggable builds only. Used instead of the Play install referrer. |
| `debugReset` | `false` | Debuggable builds only. Forgets the SDK's saved state at each start. |
| `debugApiUrl` | none | Debuggable builds only. Sends the reports to another origin. |

Kotlin: `Yousie.init(context, key, consent, YousieOptions(logging = true))`. Flutter: named parameters of `Yousie.init`.

## Test your integration

A build that was not installed from Google Play gets no referrer, so a debuggable build can be handed one:

```kotlin
Yousie.init(
    this, "ysk_your_sdk_key", YousieConsent.GRANTED,
    YousieOptions(
        logging = true,
        debugReferrer = "utm_source=yousie&utm_medium=affiliate&utm_campaign=<code>&yclid=<click id>",
        debugReset = true,
    ),
)
```

Then watch the SDK work:

```
adb logcat -s Yousie
```

```
Yousie: referrer checked: click id AbCdEfGhIjKlMnOpQrSt12
Yousie: install: attributed ins_0123456789abcdef
Yousie: purchase GPA.3345-1234-5678-90123: recorded
```

A release build ignores the three `debug…` options.

## What leaves the phone

**Install report:** your SDK key, the click id, a random install id made for Yousie only (a UUID), `android`, your app's version.

**Subscription report:** your SDK key, the same install id, the store's order id (or `tok:` and a hash of the purchase token when there is no order id), the product id, the price and currency when you give them, the trial flag.

**Never read or sent:** the advertising ID, the Android ID, the phone model, the purchase token itself, any account or contact data. Requests carry a `User-Agent` that names this SDK and its version, in place of Android's default one, which states the phone model.

You are responsible for declaring this data flow in Google Play's Data safety form and in your privacy policy.

## How it behaves

- Every call returns at once. The work runs on one background thread.
- Nothing the SDK does can throw into your app.
- No timers. Work happens at launch, when consent changes, when a purchase is reported and, on installs with a click id only, when the app returns to the foreground.
- Retries are bounded. A report that got no answer is tried again on the next launch, for a week. A purchase waits in a small saved queue, for a week.
- What it saves is in its own preferences file, `com.yousie.sdk`. Android's Auto Backup carries it to a reinstall, so a user who reinstalls is not reported twice.

## Working on the SDK

```
./gradlew :yousie:testDebugUnitTest      # Android library unit tests
cd flutter && flutter test               # Flutter plugin tests
cd flutter/example && flutter run        # example app (see its README)
```

`LocalServerTest` runs the engine and the real HTTP client against a running Yousie server. It is skipped unless you name one; see the comment at the top of the file.

The Flutter plugin compiles the Android library's sources directly (`flutter/android/build.gradle.kts` points at `android/src/main/kotlin`), so the two packages are always the same code.

## Licence

MIT. See [LICENSE](LICENSE).

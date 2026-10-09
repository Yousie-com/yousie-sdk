import 'package:flutter/material.dart';
import 'package:yousie/yousie.dart';

// The example is driven from the command line, so nothing about your app
// lives in the source:
//
//   flutter run \
//     --dart-define=YOUSIE_SDK_KEY=ysk_… \
//     --dart-define=YOUSIE_DEBUG_REFERRER='utm_source=yousie&utm_medium=affiliate&utm_campaign=<code>&yclid=<click id>'
//
// Then `adb logcat -s Yousie` shows what the SDK does.
const _sdkKey = String.fromEnvironment('YOUSIE_SDK_KEY');
const _debugReferrer = String.fromEnvironment('YOUSIE_DEBUG_REFERRER');
const _debugApiUrl = String.fromEnvironment('YOUSIE_API_URL');
const _startConsent = String.fromEnvironment('YOUSIE_CONSENT', defaultValue: 'granted');
// A test order id: when given, one subscription is reported a few seconds
// after the start, as the app's purchase code would do.
const _testOrder = String.fromEnvironment('YOUSIE_TEST_ORDER');

YousieConsent _consentNamed(String name) => YousieConsent.values.firstWhere(
  (consent) => consent.name == name,
  orElse: () => YousieConsent.unknown,
);

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  Yousie.init(
    sdkKey: _sdkKey,
    consent: _consentNamed(_startConsent),
    logging: true,
    debugReferrer: _debugReferrer.isEmpty ? null : _debugReferrer,
    debugApiUrl: _debugApiUrl.isEmpty ? null : _debugApiUrl,
    debugReset: true,
  );
  if (_testOrder.isNotEmpty) {
    Future<void>.delayed(const Duration(seconds: 5), _reportTestSubscription);
  }
  runApp(const ExampleApp());
}

Future<void> _reportTestSubscription() => Yousie.trackSubscription(
  productId: 'premium_yearly',
  orderId: _testOrder.isEmpty ? 'TEST.0001' : _testOrder,
  price: 29.99,
  currency: 'EUR',
  trial: true,
);

class ExampleApp extends StatefulWidget {
  const ExampleApp({super.key});

  @override
  State<ExampleApp> createState() => _ExampleAppState();
}

class _ExampleAppState extends State<ExampleApp> {
  YousieConsent _consent = _consentNamed(_startConsent);

  void _state(YousieConsent consent) {
    Yousie.setConsent(consent);
    setState(() => _consent = consent);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Yousie example',
      home: Scaffold(
        appBar: AppBar(title: const Text('Yousie example')),
        body: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Text(
              _sdkKey.isEmpty
                  ? 'No SDK key. Start the app with --dart-define=YOUSIE_SDK_KEY=ysk_…'
                  : 'SDK started. Watch it with: adb logcat -s Yousie',
            ),
            const SizedBox(height: 12),
            Text('Consent stated: ${_consent.name}'),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: () => _state(YousieConsent.granted),
              child: const Text('The user says yes'),
            ),
            OutlinedButton(
              onPressed: () => _state(YousieConsent.denied),
              child: const Text('The user says no'),
            ),
            const SizedBox(height: 24),
            FilledButton.tonal(
              onPressed: _reportTestSubscription,
              child: const Text('Report a test subscription'),
            ),
          ],
        ),
      ),
    );
  }
}

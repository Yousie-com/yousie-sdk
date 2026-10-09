# Yousie example

A small app that starts the SDK and lets you state consent and report a test subscription.

```
flutter run \
  --dart-define=YOUSIE_SDK_KEY=ysk_your_sdk_key \
  --dart-define=YOUSIE_DEBUG_REFERRER='utm_source=yousie&utm_medium=affiliate&utm_campaign=<code>&yclid=<click id>'
```

Then, in another terminal:

```
adb logcat -s Yousie
```

Other defines:

| Define | What it does |
|---|---|
| `YOUSIE_CONSENT` | `granted` (default), `denied` or `unknown`: the consent stated at start. |
| `YOUSIE_TEST_ORDER` | An order id. One test subscription is reported a few seconds after the start. |
| `YOUSIE_API_URL` | Another origin than `https://yousie.com`, for whoever works on the SDK. |

The example forgets the SDK's saved state at each start (`debugReset`), so the whole flow replays every time.

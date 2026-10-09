# Changelog

## 1.0.0

First release.

- Android library (`com.yousie.sdk`) and Flutter plugin (`yousie`).
- Reads the Google Play install referrer once and reports an install that came through a creator's Yousie link.
- Reports a subscription: automatically from the app's own Play Billing purchases (Billing Library 7 or newer), or with `trackSubscription`.
- Consent: `GRANTED`, `DENIED`, `UNKNOWN`.
- Debug options for testing: `debugReferrer`, `debugReset`, `debugApiUrl`.

# Target sign-in in Fast checkout: diagnosis, 2026-11-01

App v1.0.37 (installed from the in-app banner; full-screen alerts re-allowed afterwards).
Phone: Pixel 11 Pro Fold, Android 17, WebView/Chrome 156.0.8078.47. No code was changed.

## Result

Continue does nothing because Target's sign-in page goes straight to a passkey
(WebAuthn) flow for this account, and the app's WebView has no WebAuthn API.
The page's script calls `PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable()`,
`PublicKeyCredential` is `undefined`, and the uncaught `TypeError` ends the
flow. No error is shown, and no password step appears.

It is not a bot block. Every request after Continue returned 200/201.

## Sequence after tapping Continue (15:39:33, CDP log `t=4.0 s`)

1. `POST https://gsp.target.com/gsp/authentications/v1/passwordless_preauthentications` → **200**
   ```
   {"challenge":"<redacted>","user_verification":"required",
    "allow_credentials":[{"id":"<redacted>","type":"public-key","domain":"target.com"}],
    "timeout":"30000","rp_id":"target.com"}
   ```
   Target holds a registered passkey for this account, so it asks for a WebAuthn assertion instead of a password.
2. The page then throws (three times, at `t=4.02`, `4.34` and `4.35`):
   ```
   TypeError: Cannot read properties of undefined (reading 'isUserVerifyingPlatformAuthenticatorAvailable')
       at s  (https://assets.targetimg1.com/webui/login/_next/static/chunks/6345.ca0b173f2f522fb5.js:4:63737)
       at iV (https://assets.targetimg1.com/webui/login/_next/static/chunks/6345.ca0b173f2f522fb5.js:4:1168)
   ```
3. Nothing else is sent. There is no `navigator.credentials.get` call, no navigation and no password form.
   The only other requests were telemetry (`firefly_events` 201, `telemetry_data/v1/traces` 200).

Page probe over CDP, run before the tap:

| | value |
|---|---|
| `typeof PublicKeyCredential` | `"undefined"` |
| `typeof navigator.credentials.get` | `"function"` |
| `navigator.userAgentData` brands | `Not:A-Brand 8, Chromium 156, Google Chrome 156` |
| user agent | Chrome 156 Mobile, with `; wv` removed |
| `typeof window.chrome` | `"undefined"` |
| `navigator.webdriver` | `false` |

The app presents itself as full Google Chrome (UA and client-hint brands), so
Target's script assumes WebAuthn exists and doesn't feature-detect it. Real
Chrome would show the passkey sheet at this point.

The same `TypeError` fires on page load and on every keystroke in the email
field (17 times before the tap), so the passkey check runs constantly, not only
on Continue.

## Other lines, not the cause

- `Loading the script 'https://client.px-cloud.net/PXGWPp4wUS/main.min.js' violates the following Content Security Policy directive: "script-src 'self' https://*.target.com …"`: Target's own CSP blocks the HUMAN/PerimeterX loader on the login page. This is Target's configuration and would happen in Chrome too. It didn't cause a block: no request was refused.
- `HTTP 405 GET https://ad.doubleclick.net/ccm/s/collect…`: an ad pixel.
- The `enforcer-jsref…js was preloaded … but not used` warning: harmless.

## What would fix it (for the code session; not done here)

- Enable WebAuthn in the WebView: androidx.webkit `WebSettingsCompat.setWebAuthenticationSupport(settings, WEB_AUTHENTICATION_SUPPORT_FOR_APP)`, behind `WebViewFeature.isFeatureSupported(WEB_AUTHENTICATION)`. For an app that isn't a browser, the passkey for `target.com` only works if target.com's `assetlinks.json` lists this app, which it won't. Expect this to fail for Target's rp_id, so it's probably not the fix.
- Make the page take its non-passkey path. Clear the Chrome brand from the client hints so the page sees an embedded browser, or inject a `PublicKeyCredential` stub whose `isUserVerifyingPlatformAuthenticatorAvailable()` resolves `false` before page scripts run (`addDocumentStartJavaScript`). Whether Target then falls back to a password or OTP step is untested.
- Sign in through Chrome (the new CHROME button) instead. That doesn't put cookies in the app's jar, so assisted add-to-cart in the WebView would still see a guest.
- Or remove the passkey from the Target account so the preauth returns a password challenge. This is an account change, so it's the user's call.

## Evidence

- `target-signin-2026-11-01/cdp.log`: Runtime, Log and Network events for 30 s around the tap. The challenge, credential id and telemetry key are redacted, and the repeated pre-tap exception is collapsed.
- `target-signin-2026-11-01/after-continue.png`: the page after Continue, unchanged, with the email field blanked.

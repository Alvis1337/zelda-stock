# Phone test reports

Pixel 11 Pro Fold, Android 17. All runs on 2026-11-01. The reports are in the order they were written, and later ones supersede earlier results. This page has the current state.

| Report | What it covers |
|---|---|
| [phone-2026-11-01.md](phone-2026-11-01.md) | Full plan T1–T16 on v1.0.28 (the baseline, with the original failures) |
| [target-signin-2026-11-01.md](target-signin-2026-11-01.md) | Why Target sign-in hung (passkey API missing in the WebView) and the fix |
| [phone-2026-11-01-T9-rerun.md](phone-2026-11-01-T9-rerun.md) | T9 (restock → add to cart) at Best Buy and Target, v1.0.39 → v1.0.45 |
| [phone-2026-11-01-fixes-rerun.md](phone-2026-11-01-fixes-rerun.md) | T5, T10, T13 and the notification surprises, v1.0.45 → v1.0.48 |
| [phone-2026-11-01-other-stores.md](phone-2026-11-01-other-stores.md) | Assisted add at Walmart, Amazon and Nintendo, v1.0.48 → v1.0.54 |

Evidence (screenshots, timelines, dumps) is in the folder with the same name as each report.

## Current state (last tested on v1.0.54)

| Area | State | Last checked |
|---|---|---|
| Push alerts (UnifiedPush/ntfy), Doze, server status notifications | pass | v1.0.28 |
| Restock alert opens Checkout over the lock screen | pass, ~3.5 s from the API call | v1.0.54 |
| Repeat alert for the same store; several alerts at once | pass | v1.0.45 |
| Alarm with the Pixel Watch connected | Checkout plays its own alarm (pass). The plain TEST ALARM notification is still muted by the watch (left as is, owner's choice) | v1.0.45 |
| Full-screen permission after an update | updates to 1.0.28–1.0.45 reset it to deny; the app now posts "Allow full-screen alerts again" and clears it once allowed. Updates to 1.0.51 and 1.0.54 didn't reset it | v1.0.54 |
| Offline | pass ("no internet connection", last results kept). The baseline's T13 result was likely invalid: airplane mode kept Wi-Fi on | v1.0.45 |
| Best Buy add | pass ($22.99) | v1.0.48 |
| Target add (and sign-in) | pass ($14.99) | v1.0.48 |
| Walmart add | pass ($6.88) | v1.0.54 |
| Amazon add | adds and the seller check passes, but the guard quoted the list price ($10.05 vs $9.49). Fixed in 4ca6aa9, **not yet retested** | v1.0.54 |
| Nintendo add | pass ($6.99) | v1.0.48 |
| Guards: price over $560, third-party seller, sold-out page | pass | v1.0.48 |

Minor, open:
- When an alert reuses an already-open Checkout, the status bar shows the previous run's "added…" for a few seconds.
- After a locked repeat alert, that store's notification can stay in the shade while Checkout is showing.

## Notes for whoever runs the next test

- Never commit account details. Screenshots of signed-in pages are blanked where they show a name with an address, a phone number or an email. Cart screenshots that show the owner's own items are left out.
- Every PNG here was OCR-checked on 2026-11-01 for credentials and contact details. One Cloudflare Access client ID was found and blanked (`phone-2026-11-01/T12-1-wrong-secret.png`). It is still in the git history of commit fcfefe4.
- The phone's unlock PIN is never written anywhere in the repo.

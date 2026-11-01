# Rerun of the other fixed failures, 2026-11-01 (app v1.0.45)

Rerun of the failures and surprises from [phone-2026-11-01.md](phone-2026-11-01.md) that commit 458e4d4 says it fixed. T9 has its own rerun in [phone-2026-11-01-T9-rerun.md](phone-2026-11-01-T9-rerun.md).
16:43–16:56 EST. Nothing was added to any cart, and all carts were checked at 0 / $0.00 afterwards. Evidence is in [phone-2026-11-01-fixes-rerun/](phone-2026-11-01-fixes-rerun/).

| Item | Was | Now | Result |
|---|---|---|---|
| T10.1 Best Buy TV $1,099.99 | "$109999.00 (over $560)" | **"$12999.99 (over $560)"**. Refused, but the price is another TV's | PASS, new bug |
| T10.2 Walmart marketplace (E-Tech Galaxy, $548.94) | "$54894.00 (over $560)", so the seller check was never reached | "Sold by "E-Tech Galaxy LLC", not Walmart. Not adding." | **PASS** |
| T10.3 Walmart sold-out Zelda listing | "$52900.00" from a carousel item | "The Add to cart button is for another product; this one looks sold out. Not adding." | **PASS** |
| Surprise 5: repeat alert, same store, first still in the shade | no Checkout, screen stayed dark | Checkout opened over the lock screen | **PASS** |
| Surprise 6: grouped alerts open the main screen | tap opened MainActivity | alerts show as separate cards; tap opened Checkout | **PASS** |
| T13 offline | no error line (see note) | "Could not reach the server: no internet connection. Showing the last results." at once; "Last server check" turns red | **PASS** |
| T5 alarm silent while the watch is connected | no sound | Checkout plays its own `USAGE_ALARM` player over the lock screen while the watch hint is active | **PASS** (sound kept to ~2 s) |
| T16 status-bar icons on light screens | nearly invisible | dark icons visible on the main screen (T13 screenshot) | PASS |
| Update resets full-screen alerts to deny | — | still happens (1.0.37, 1.0.39, 1.0.45 today) | **not fixed** |

## T10.1: Best Buy price guard picks up the "LG lineup" strip

LG 65" B6E OLED, SKU 6680627, $1,099.99 (was $1,899.99), sold by Best Buy. Phone unlocked; tapped the heads-up.
Status at t+9.9 s: "Best Buy: Price shows $12999.99 (over $560). Not adding: likely a reseller." Cart 0 items.

Over DevTools: between the `<h1>` (y=727) and the Add to cart button, Best Buy renders a strip of other LG TVs, each with a `[data-testid=price-block-customer-price]` (y=1302). The prices run from $149.99 to "LG - 115" Class 92B Series … **$12,999.99**". The guard takes the highest amount in the title-to-button range, so it quoted the 115" set.

The refusal is right here, but for the wrong reason. The risk runs the other way for the console: if the Zelda page shows a strip like this with anything over $560 (bundles, other consoles), a real $519.99 restock would be refused as "likely a reseller". Possible fix: on Best Buy, read only the first `price-block-customer-price` inside the main price block (`[data-testid=price-block-content]` nearest the title), or skip `.c-tile` / carousel tiles.

## T10.2 and T10.3: Walmart seller and sold-out guards

- 18175002206 (Switch 2 + Pokémon Legends Z-A bundle, $548.94, "Sold and shipped by E-Tech Galaxy LLC"): with the ×100 bug fixed, the price is under $560 and the seller check now stops it. Cart $0.00 (T10-2).
- 21002656445 (Zelda 40th, out of stock): "The Add to cart button is for another product; this one looks sold out. Not adding." Cart $0.00 (T10-3).

## Surprise 5: repeat alert

1. Phone unlocked, alert 1 (Best Buy TV) posted as a heads-up and left in the shade (id -1955650632). Focus stayed on the launcher.
2. Screen off and locked, alert 2 for the same store: Checkout took focus over the lock screen and the screen woke (S5).

Also with Checkout already open from a first locked alert: a second locked alert woke the screen and brought Checkout back.

## Surprise 6: grouping

Best Buy then Walmart alerts while unlocked. The shade shows two separate cards, not a collapsed "Zelda Stock · 2" (S6). Android puts them in its own `g:Aggregate_AlertingSection` autogroup, but they stay expanded. Tapping the older (Best Buy) card opened Checkout on Best Buy.

## T13: offline

Note on the first run: airplane mode on this phone keeps Wi-Fi connected (Wi-Fi had been turned on in airplane mode before), and `dumpsys connectivity` still showed WIFI CONNECTED as the default network. So in the first run's T13 the phone may never have been offline, which would explain "no error line". This rerun turned off Wi-Fi as well (`svc wifi disable`; active default network: none).

- App force-stopped and reopened: "Last server check: 7 min ago" in red, "Could not reach the server: no internet connection. Showing the last results." The update card says "update check failed: Unable to resolve host "api.github.com"". Results list kept (T13).
- Refresh: the same line at +3 s and +28 s. No crash.
- Restored: airplane mode off, Wi-Fi on (`airplane_mode_on=0`, `wifi_on=1`), default network back. Refresh → "Last server check: 3 min ago / None in stock …".

## T5: alarm over the lock screen with the watch connected

`mListenerHints: 2` (watch companion muting notification effects) was active. Screen off and locked, in-stock alert at 16:55:34; Checkout took focus at 16:55:38. 1.5 s later `dumpsys audio`:

```
AudioPlaybackConfiguration piid:4087 deviceIds:[4] type:android.media.MediaPlayer u/pid:10483/15392 state:started
  attr:AudioAttributes: usage=USAGE_ALARM content=CONTENT_TYPE_SONIFICATION
```

uid 10483 is Zelda Stock. A key press (`KEYCODE_SHIFT_LEFT`) stopped it, and no alarm player was left afterwards. At the owner's request the sound was cut after ~2 s, so the "rings until touched, max 60 s" timeout wasn't timed. The plain TEST ALARM button (a notification, not Checkout) wasn't retested; it would still be muted by the watch.

## Still open

- Every app update resets `USE_FULL_SCREEN_INTENT` to deny. It was re-allowed by hand after 1.0.37, 1.0.39 and 1.0.45. Until it's re-allowed, an in-stock alert won't open Checkout over the lock screen.
- Best Buy price guard reads the related-products strip (T10.1 above).
- After surprise 5's locked repeat, the notification for that store stayed in the shade even though Checkout was showing. That's harmless, but it isn't the "one sound, not two" cancel the code intends.

## v1.0.48 (commit 7dc4879), 17:19–17:23

| Item | Result |
|---|---|
| Full-screen reset after update | **Reminder works.** After the 1.0.48 install the app posted "Allow full-screen alerts again: The update turned off full-screen alerts, so a restock can't open checkout on the lock screen. Tap to allow them again." Full-screen alerts were allowed again. Small note: the reminder was still in the shade after the permission was back on. |
| T10.1 Best Buy TV $1,099.99 | **PASS.** "Best Buy: Price shows $1099.99 (over $560). Not adding: likely a reseller." (t+10.5 s). Cart 0 items. |
| T9 Best Buy cable $22.99 (locked) | **PASS.** Over the lock screen t+3.6 s, "Adding to cart ($22.99)…" t+6.5 s, added by t+12.9 s. Cart 1 item → removed → "Your cart is empty." |
| T9 Target cable $14.99 (locked) | **PASS.** "Adding to cart ($14.99)…" by t+3.6 s, added by t+12.4 s. Cart 1 item → removed → "cart 0 items". |

The alarm was hushed with a key press as before. Timelines and empty-cart screenshots are prefixed `v1.0.48-`.

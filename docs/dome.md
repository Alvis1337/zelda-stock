# Next phone run: Walmart and Amazon checkout on v1.0.51

For the phone-testing session (phone on USB, `adb`, `kubectl`). Same ground rules as
[phone-test-plan.md](phone-test-plan.md): never place an order or tap Place order / Buy
now / Pay / Checkout, remove every test item from the cart, put back anything you change,
and touch the server only through `deploy/zs-api.sh`. Test and report; don't change code.

What changed since [phone-2026-11-01-other-stores.md](test-reports/phone-2026-11-01-other-stores.md)
(commit 47b0dd6):

- Walmart: the price guard reads `[itemprop="price"]`, not the title-to-button text (which
  quoted a "$35 welcome bonus").
- Walmart: when Add to cart jumps to `walmart.com/pac`, the app now says "added. Opening
  cart…" and loads the cart itself.
- Amazon: the title is `#productTitle` / `#title` (not the `#pqv-ingress` screen-reader h1),
  and the 4,000-character "button for another product" check is skipped on Amazon.
- The "Allow full-screen alerts again" reminder clears itself once full-screen alerts are
  allowed.

## 1. Update and the reminder

1. `git pull`. Open the app and update from its banner to **v1.0.51** or newer
   (`adb shell dumpsys package me.chrisalvis.zeldastock | grep versionName`).
2. Right after the install, the shade should show "Allow full-screen alerts again". 📸
3. Tap it, allow full-screen alerts, go back to the app.
4. Check the reminder is **gone** from the shade
   (`adb shell dumpsys notification --noredact | grep -A5 "pkg=me.chrisalvis.zeldastock"`). 📸
5. `adb shell appops get me.chrisalvis.zeldastock USE_FULL_SCREEN_INTENT` must say allow.

## 2. Walmart (guest is fine)

Same item as last time: onn 10ft USB-C cable, https://www.walmart.com/ip/14823970315, $6.88,
sold by Walmart. Check it's still in stock and sold by Walmart first.

1. Phone screen off and locked.
2. `deploy/zs-api.sh POST /api/test-alert '{"kind":"in_stock","retailer":"Walmart","product":"onn USB-C cable (test)","url":"https://www.walmart.com/ip/14823970315"}'`
3. Record the status bar every ~3 s from the API call (timeline file), and 📸 each new status.
   Expected:
   - "Walmart: Adding to cart ($6.88)…" (the real price, not $35)
   - then "Walmart: added. Opening cart…"
   - ending on the Walmart **cart** with "added. Check out from here.", 1 item, $6.88.
4. Remove the item. 📸 the empty cart.

## 3. Amazon

Sign-in is optional. If the owner wants to sign in first (Fast checkout › Amazon), let them
type it; don't type credentials yourself. Note in the report whether it was signed in.

Same item: Amazon Basics USB-C cable, https://www.amazon.com/dp/B01GGKZ2SC, "Shipped and
sold by Amazon.com".

1. Phone screen off and locked.
2. `deploy/zs-api.sh POST /api/test-alert '{"kind":"in_stock","retailer":"Amazon","product":"Amazon Basics USB-C cable (test)","url":"https://www.amazon.com/dp/B01GGKZ2SC"}'`
3. Timeline as above. Expected: "Amazon: Adding to cart ($…)…" with the page's price, then
   "added. Check out from here." on the Amazon cart, 1 item. If Amazon shows a bot check or
   a sign-in wall instead, record the exact status text and 📸.
4. Remove the item. 📸 the empty cart.

## 4. Alarm

The owner may ask to keep it quiet: as before, a key press (`adb shell input keyevent
KEYCODE_SHIFT_LEFT`) as soon as Checkout takes focus stops it. Say in the report if you did.

## Report

Append a section "v1.0.51 (commit 47b0dd6)" to
[phone-2026-11-01-other-stores.md](test-reports/phone-2026-11-01-other-stores.md) with a
table (store, price read, result, landed on), the exact status texts, and timings from the
API call. Put screenshots and timelines in `docs/test-reports/phone-2026-11-01-other-stores/`
prefixed `v1.0.51-`. Then:

```bash
git add docs/test-reports && git commit -m "Walmart and Amazon checkout on v1.0.51" && git push
```

Reply with the table.

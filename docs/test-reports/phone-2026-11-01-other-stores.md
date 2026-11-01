# Checkout assist at Walmart, Amazon and Nintendo, 2026-11-01 (app v1.0.48)

T9 only covers Best Buy and Target. This runs the same check at the other three Fast checkout stores, with a cheap in-stock item each store sells itself. Each run: phone screen off and locked, `deploy/zs-api.sh POST /api/test-alert` with `kind: in_stock`, status bar recorded every few seconds, then the test item removed. The alarm was cut off with a key press as soon as Checkout appeared, at the owner's request. Nothing was ordered. Evidence is in [phone-2026-11-01-other-stores/](phone-2026-11-01-other-stores/).

| Store | Item | Price read | Result |
|---|---|---|---|
| Walmart (guest) | onn 10ft USB-C cable, `/ip/14823970315`, $6.88, sold by Walmart | **$35.00** | Added, with **two bugs**: wrong price quoted; status stuck on "Adding to cart…", never opened the cart |
| Amazon (not signed in) | Amazon Basics USB-C cable, `/dp/B01GGKZ2SC`, In Stock, "Shipped and sold by Amazon.com" | — | **FAIL**: false refusal, "The Add to cart button is for another product; this one looks sold out." |
| Nintendo (signed in) | Korok Enamel Pin, $6.99 | $6.99 | **PASS**: added within 7.4 s, ended on the cart |

GameStop has no Fast checkout button (the server only watches it), so it wasn't tested.

## Walmart (17:24:55)

- t+3.8 s Checkout over the lock screen, "opening the page…"; t+6.7 s **"Walmart: Adding to cart ($35.00)…"**, which stayed for the rest of the 30 s.
- The item was added: Walmart's "Added to cart!" page `/pac?id=14823970315&…` with "View cart (1)", subtotal $6.88 (walmart-1). Cart: 1 item $6.88 → removed → "Cart contains 0 items, $0.00" (walmart-2).

**Bug 1, price:** Walmart has no `MAIN_PRICE` selector in `checks/assist.js`, so the guard reads all text from the title to the button. That stretch contains "See how to earn a **$35** welcome bonus with the OnePay credit card", and the guard takes the highest amount. The real price is in `[itemprop="price"]` ("$6.88"; `[data-fs-element="price"]` also works). Adding Walmart to `MAIN_PRICE` with `[itemprop="price"]` should fix it. The impact on the console is mainly a wrong number in the status: a $35 bonus is below $519.99, so it wouldn't raise the max over $560. But a bigger promo amount (e.g. "$600 off …") in that stretch would cause a false refusal.

**Bug 2, status/cart:** clicking Walmart's Add to cart navigates straight to `/pac`, which unloads the page and kills the assist script during its `zsSleep(3500)`. So "Added. Opening cart" never shows and `location.href = CART` never runs. The item is in the cart and Walmart's own page offers "View cart (1)", so this isn't blocking, but the status bar is wrong. The app could treat a navigation to `/pac` (or any page load after the click) as "added" from the native side (`onPageFinished`).

## Amazon (17:27:19)

- t+4.5 s Checkout over the lock screen; t+11.7 s "Amazon: The Add to cart button is for another product; this one looks sold out. Not adding." Nothing added.
- DevTools on the page: `#add-to-cart-button` present, visible, enabled; `#availability` "In Stock"; merchant "Shipped and sold by Amazon.com".
- Cause: assist uses the first visible `h1` as the title. On Amazon's mobile page that's `h1#pqv-ingress`, "Product summary presents key product information", a screen-reader heading above the sponsored strip. The real title is a later `h1` ("Amazon Basics USB-C to USB-C Fast Charger Cable…"). Measured from `#pqv-ingress`, the title-to-button text is **6,990 characters**. The sold-out guard treats anything over 4,000 as "button for another product".
- Fix direction: skip `#pqv-ingress` / prefer `#productTitle`, `#title` or the `h1` that contains the product name. Or don't apply the 4,000-character check on Amazon, where the button is found by its fixed id `#add-to-cart-button` and can't be a carousel's.
- Not signed in at Amazon. Whether a guest add works wasn't reached.

## Nintendo (17:29:20)

- Cart before: 1 item, the Zelda 40th console from this morning's real alert ($519.99, out of stock) (nintendo-0). Left as is.
- t+3.5 s "Nintendo: Adding to cart ($6.99)…" over the lock screen; t+7.4 s "Nintendo: added. Check out from here." on the cart: 2 items, $562.55 (nintendo-1).
- Removed only the pin → "Removed The Legend of Zelda™ Korok Enamel Pin from your cart.", "Estimated total (1 item)" (nintendo-2). The console is still there.
- The Checkout WebView was already on nintendo.com from browsing for the item, which may explain the quick load (earlier T15 saw 10–20 s for a cold Nintendo page).

## v1.0.51 (commit 47b0dd6), 17:42–17:47

Run per [docs/dome.md](../dome.md). Phone screen off and locked for each alert. The alarm was cut off with `KEYCODE_SHIFT_LEFT` as soon as Checkout took focus, at the owner's request. Nothing was ordered; both carts were emptied.

| Store | Signed in | Price read | Result | Landed on | Timing from the API call |
|---|---|---|---|---|---|
| Walmart | no (guest) | **$6.88** (correct) | **PASS** | `walmart.com/cart`, 1 item $6.88 | run 2: product page t+6.2 s, "Adding to cart ($6.88)…" t+8.1 s, `/pac` t+9.2 s, `/cart` t+9.7 s. Run 1: "added. Check out from here." on the cart by t+6.8 s |
| Amazon | yes | — (refused before the price) | **FAIL**: false refusal at the seller check | product page, nothing added (cart 0) | page t+5.6 s, refusal t+9.2 s |

### 1. Update and reminder

- v1.0.48 → **v1.0.51** at 17:42:33. The owner tapped the install and allowed full-screen alerts during this run, so the "Allow full-screen alerts again" reminder wasn't seen or photographed.
- After that: `USE_FULL_SCREEN_INTENT: allow`, the app shows "Alerts can open checkout on the lock screen by themselves.", and **no reminder in the shade**. The only app notification left was an older "In stock at Test: this is only a test". So the reminder cleared once it was allowed, as intended.

### 2. Walmart

Item check before the run (DevTools on the page): `[itemprop="price"]` "$6.88", "Sold and shipped by Walmart", Add to cart present, cart 0 items.

Exact statuses. Run 2 polled `window.__zsaStatus` every 200 ms (`v1.0.51-walmart-run2-status-poll.txt`); the status bar was read every ~3–4 s:
- "Walmart: opening the page to add one to your cart…" (run 1 t+3.9 s, v1.0.51-walmart-1)
- "Adding to cart ($6.88)…" (run 2 t+8.1 s)
- Add to cart → `walmart.com/pac?id=14823970315&ip=6.88&qt=1…` (t+9.2 s) → the app loaded `walmart.com/cart` itself (t+9.7 s)
- "Walmart: added. Check out from here." on the cart, "Cart contains 1 item Total Amount $6.88" (v1.0.51-walmart-2)
- The intermediate "added. Opening cart…" was too short-lived for the 3 s status-bar snapshots to catch.

Removed → "Cart contains 0 items Total Amount $0.00" (v1.0.51-walmart-3, -4).

Small note: in run 2 Checkout was still open from run 1, and for the first ~4 s the status bar still read run 1's "added. Check out from here." while the new alert loaded the product page. A fresh Checkout (run 1) showed "opening the page…" correctly.

### 3. Amazon

The title and the 4,000-character check are fixed: it now gets past them. It fails at the seller check instead:

```
Amazon: Sold by "Shipped and sold by Amazon.com
                                                    Shipped and sold by Amazon.com         Shipper / Seller       Amazon.com", not Amazon. Not adding.
```

(t+9.2 s, v1.0.51-amazon-1, account header blanked.) The Amazon fallback takes the whole text of `#merchantInfoFeature_feature_div .offer-display-feature-text`, which starts "Shipped and sold by Amazon.com" and repeats with whitespace and "Shipper / Seller". `OWN.Amazon` is `/^\s*amazon(\.com)?\b/i`, which needs the text to start with "Amazon". Fix direction: match the seller name inside that text (e.g. strip a leading "Shipped and sold by" / "Sold by", or test `/\bsold by amazon(\.com)?\b|seller\s*amazon(\.com)?\b/i`), and collapse whitespace before quoting it in the status. The status bar currently shows it across three lines.

Amazon was signed in for this run (the earlier "not signed in" in this report is out of date). Cart icon 0. Nothing added, so nothing to remove.

## v1.0.54 (commit 2050ae9), 17:58–18:03

Run per [docs/dome.md](../dome.md). Phone screen off and locked for each alert. The alarm was cut off with `KEYCODE_SHIFT_LEFT` as soon as Checkout took focus. Nothing was ordered. Statuses come from a 200 ms poll of `window.__zsaStatus` over DevTools (`*-status-poll.txt`) plus status-bar snapshots every ~4 s (`*-timeline.txt`).

| Store | Signed in | Price read | Result | Landed on | Timing from the API call |
|---|---|---|---|---|---|
| Amazon | yes | **$10.05** (page price $9.49) | **PASS**, with a price note | `amazon.com/gp/cart/view.html`, cable qty 1 at $9.49 | product page t+5.2 s, "Adding to cart ($10.05)…" t+11.3 s, cart t+15.0 s |
| Walmart | no (guest) | **$6.88** (correct) | **PASS** (regression check) | `walmart.com/cart`, "Cart contains 1 item Total Amount $6.88" | product page t+6.9 s, "Adding to cart ($6.88)…" t+9.3 s, `/pac` t+10.3 s, `/cart` t+10.9 s |

### Update and reminder

v1.0.51 → **v1.0.54** from the in-app banner at about 17:59 (done by the tester this time). `USE_FULL_SCREEN_INTENT` stayed **allow** after the install, so there was no reset and no "Allow full-screen alerts again" reminder. That's correct behaviour, but steps 1.2–1.4 couldn't be exercised. The last two updates (→1.0.51, →1.0.54) didn't reset the permission; the earlier ones (→1.0.28, .37, .39, .45) did.

### Amazon

Exact statuses: "Amazon: opening the page to add one to your cart…" → "Amazon: Adding to cart ($10.05)…" (v1.0.54-amazon-1, account header blanked) → "Amazon: added. Check out from here." on the cart. The seller fix works: "Shipped and sold by Amazon.com" now passes.

Cart afterwards: Amazon Basics cable B01GGKZ2SC, qty 1, $9.49, subtotal $9.49. Deleted → "… was removed." One unrelated item that was already in the owner's cart was left untouched. Cart screenshots aren't committed because they show the owner's own items.

**Price note:** the guard quoted **$10.05**, which is Amazon's "Typical price: $10.05" / "List Price: $10.05", not the $9.49 being charged. Amazon has no `MAIN_PRICE` entry, so the guard reads every amount in the buy area and takes the highest. The charged price is in `.priceToPay` (`apex-pricetopay-value`, "$9 . 49"). Impact: for the console, a listing with a list price over $560 but a selling price at or under $560 would be refused as "likely a reseller". Suggest `'Amazon': '.priceToPay'` (or `#corePrice_feature_div .priceToPay`) in `MAIN_PRICE`.

### Walmart

Same as v1.0.51: "opening the page…" → "Adding to cart ($6.88)…" → `/pac?id=14823970315&ip=6.88&qt=1…` → the app loaded `/cart` → "Walmart: added. Check out from here.", 1 item $6.88 (v1.0.54-walmart-1). Removed → "Cart contains 0 items Total Amount $0.00" (v1.0.54-walmart-2).

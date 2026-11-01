# T9 rerun, 2026-11-01 (app v1.0.39)

Rerun of T9 from `docs/phone-test-plan.md` (first run: `phone-2026-11-01.md`) after the assist price fixes (458e4d4) and Target sign-in fix (5b3d130).
Same items as the first run. Phone screen off and locked before each call. Best Buy and Target are both signed in now.

| Retailer | Result | API call → added | Notes |
|---|---|---|---|
| Best Buy | **PASS** (note) | ≤ 7.2 s | Price read correctly. Lands on the checkout page, not the cart. |
| Target | **PASS with bug** | ≤ 9.5 s | Added, but the price guard read **$50.00** for a $14.99 item. |

Both carts were emptied afterwards, and nothing was ordered.

## Best Buy (15:55:29)

Insignia USB-C braided cables (3-pack), SKU 6587233, $22.99, sold by Best Buy.

- t+3.5 s: Checkout over the lock screen (`isKeyguardShowing=true`), "Best Buy: opening the page to add one to your cart…" (T9-2).
- t+7.2 s: "Best Buy: Added. Opening cart: check out from here." (T9-3). The "checking price" and "Adding to cart ($x)" statuses went by between screenshots.
- **Surprise:** the app loads `https://www.bestbuy.com/cart`, but the WebView ended on `https://www.bestbuy.com/checkout/c/standard`: "Checkout, Total $24.54, 1 item", with contact info and pickup filled in (T9-4, contact details blanked). Best Buy seems to redirect a signed-in cart straight into checkout. One tap away from Place order is fine for speed, but it isn't the "cart" the status and plan describe. Nothing was tapped there except "Back to cart".
- Cart: 1 item, $22.99 (T9-5). Removed → "Your cart is empty." (T9-6).

Last run this read "$2299.00 (over $560)". That's fixed.

## Target (15:59:17)

heyday 6' USB-C to USB-C 240W braided cable, https://www.target.com/p/-/A-95012793, $14.99, in stock at Lancaster.

- t+3.5 s: Checkout over the lock screen, "Target: opening the page to add one to your cart…" (T9-7).
- t+6.3 s: **"Target: Adding to cart ($50.00)…"** on the product page (T9-8).
- t+9.5 s: "Added to cart" drawer, then the status "added. Check out from here." (T9-9). Cart: 1 item, $14.99, "$16.00 est. total" (T9-10).
- Removed → "cart 0 items" (T9-11). Nothing else was in the Target cart before or after.

Last run this said "Could not read the price". It now adds.

(The first Target attempt at 15:58:28 never went out: my wrapper used `timeout`, which macOS doesn't have. It isn't counted.)

### Bug: Target price guard reads the payment-options box, not the price

Reproduced over DevTools on the same page, running the steps from `checks/assist.js`:

1. The text between the `<h1>` and the Add to cart button holds no dollar amount (411 chars, 0 matches). Target renders the price outside that range.
2. So assist falls back to "the closest block around the button that has one", which is 6 levels up from the button. That block is Target's payment-options box:
   ```
   … Target Circle™ Card (restrictions apply) Pay over time With Affirm on orders over $50
   Pay in 4 interest-free payments of $3.75 With PayPal …
   ```
3. Its prices are `$50` and `$3.75`. The guard takes the max, **$50.00**. The actual $14.99 isn't in the block.

Impact: at Target the guard isn't checking the item's price. It's checking whatever amounts sit in the financing box. On the console page that box would hold something like "orders over $50" and "4 payments of $130.00", so a listing far over $560 (a Target Plus partner, say) could pass. The `(?!\s*\/\s*mo)` exclusion doesn't help, because these amounts have no "/mo".

## Other

- Before the run, the app's full-screen permission had been reset to deny by the v1.0.39 update. It was re-allowed in the app (same as after 1.0.37). Without that, step 3 would only have been a heads-up.
- `isKeyguardShowing=true` stayed true throughout both runs, and Checkout drew over the lock screen both times.

## Evidence (`phone-2026-11-01-T9-rerun/`)

T9-1 … T9-11 screenshots, `T9-bestbuy-timeline.txt` and `T9-target-timeline.txt` (status bar every ~3.5 s from the API call).

## Target again on v1.0.42 (commit 8bc1112, price element instead of financing box)

**FAIL, safely.** Two runs, 16:16:22 and 16:24:57, phone locked. Both times Checkout opened over the lock screen (T9-12) and by t+3.4 to 6.2 s said "Target: Could not read the price. Not adding automatically; check the page." Nothing was added, and the cart stayed at 0 items. Timelines: `T9-target-v1.0.42-timeline.txt`, `T9-target-v1.0.42-run2-timeline.txt`.

The fix no longer quotes the financing box. The new problem is that **Target only renders the price once it's scrolled into view**, and assist reads before any scroll:

- Right after the alert load, `[data-test="module-product-detail-price-v2"]` is present and empty, and "14.99" isn't in the page HTML (T9-13: the page goes from "Rarely returned" straight to the product image).
- One swipe on the phone, and a second later the box reads `$14.99` (T9-15).
- Reproduced by script over DevTools (reload, no touch):
  ```
  t+3s  no scroll: scrollY=0 box=""
  t+6s  no scroll: scrollY=0 box=""
  t+10s no scroll: scrollY=0 box=""
  after scrollIntoView +0.5s: scrollY=548 box=""
  after scrollIntoView +1.5s: scrollY=548 box="$14.99"
  ```
- This also explains the 15:59 run: with no price rendered, the old code fell back to the financing box ($50.00).

Fix direction for the code session: before reading the price at Target, call `scrollIntoView({block:'center'})` on `[data-test="module-product-detail-price-v2"]` (or scroll near the button), then wait up to ~3 s for it to contain a dollar amount. A scripted scroll is enough; no touch is needed.

**Correction:** an earlier version of this section blamed Target's bot protection. That was wrong. During one manual reload, a signed-in `deferred_enrichment/modules?auth=true` request did return **435** (HUMAN/PerimeterX), and Target then showed a "Press & hold" challenge (T9-14, reference id blanked), which the user cleared. But the empty price box is explained by the lazy render alone: it fills on scroll in runs with no challenge. The 435 is worth knowing about as a separate risk (a challenge mid-alert would block adding), but it isn't the cause here.

## Full T9 on v1.0.45 (commit 52763ef, scroll Target's price box into view), 16:38–16:41

**PASS at both stores.** Phone locked and screen off before each call. Both carts were emptied afterwards, and nothing was ordered.

| Retailer | Over lock screen | Price read | In cart | Landed on |
|---|---|---|---|---|
| Best Buy (16:38:53) | t+3.3 s | **$22.99** (correct) | ≤ t+14.3 s | `/cart` (T9-18) |
| Target (16:40:04) | t+3.4 s | **$14.99** (correct) | ≤ t+9.9 s | `/cart` (T9-22) |

- Best Buy: "opening the page…" → "Adding to cart ($22.99)…" (T9-17) → "added. Check out from here." Cart: 1 item, the Insignia 3-pack. This time it landed on the cart, not the checkout page as in the 15:55 run. Removed → "Your cart is empty." (T9-19).
- Target: "opening the page…" → "Adding to cart ($14.99)…" (T9-21) → "added. Check out from here." Cart: 1 item, $14.99. Removed → "cart 0 items" (T9-23). No bot challenge this time.
- Timelines: `T9-bestbuy-v1.0.45-timeline.txt`, `T9-target-v1.0.45-timeline.txt`.

Notes:
- The v1.0.45 update again reset full-screen alerts to deny (third time today: 1.0.37, 1.0.39, 1.0.45). They were re-allowed in the app before the run.
- On request, the alarm was kept quiet. `cmd media_session volume --stream 4 --set 1` had no effect on this phone (alarm stayed at 7), so instead a `KEYCODE_SHIFT_LEFT` was sent as soon as CheckoutActivity took focus (≈4–5 s after the call). That ends the alarm through `onUserInteraction`. The ring-until-touched behaviour therefore wasn't observed in this run. Volume settings were not changed.

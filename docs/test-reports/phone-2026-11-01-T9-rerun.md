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

## Target again on v1.0.42 (commit 8bc1112, price element instead of financing box), 16:16:22

**FAIL, safely.** Checkout opened over the lock screen at t+3.4 s (T9-12). At t+6.2 s it said "Target: Could not read the price. Not adding automatically; check the page.", and that stayed (T9-13, `T9-target-v1.0.42-timeline.txt`). Nothing was added, and the cart stayed at 0 items.

The fix did what it should: it no longer quotes the financing box. But the page had **no price to read**:

- Price container `[data-test="module-product-detail-price-v2"]` is present, visible and **empty**. The other is `price-module-placeholder`. "14.99" doesn't appear anywhere in `document.documentElement.outerHTML`. The only dollar amounts in the DOM are the financing lines ("orders over $50", "payments of $3.75").
- Reload with network capture: the signed-in request that fills the price module is blocked by Target's bot protection (HUMAN/PerimeterX):
  ```
  POST https://www.target.com/cdui_orchestrations/v1/pages/pdp/deferred_enrichment/modules?auth=false&…  → 200
  POST https://www.target.com/cdui_orchestrations/v1/pages/pdp/deferred_enrichment/modules?auth=true&base_membership=true&…  → 435
       {"appId":"PXGWPp4wUS","blockScript":"https://captcha.px-cdn.net/PXGWPp4wUS/captcha.js?…"}
  ```
  A few seconds later the page was replaced by Target's "Quick verification: Press & hold to confirm you're a human" (T9-14, reference id blanked). During the alert run itself no challenge was drawn. The price module just stayed empty, so the 435 there is inferred from the identical empty module, not captured.
- In the 15:59 run (v1.0.39) the title-to-button range had no price either. That's why the old code fell back to the financing box. So Target probably wasn't serving the price to this WebView then either.

So at Target the blocker is now bot protection on the signed-in session (it began after the sign-in earlier today; the guest page showed $14.99 in the first run), not the parser. Possible directions for the code session:
- If the price module is empty but the `auth=false` response has the price, read it from there (or from `__NEXT_DATA__`/the redsky payload) instead of the DOM.
- Or detect the 435 / Press-&-hold overlay and say "Target bot check: press & hold, then tap Add" instead of "Could not read the price".
- Whether a solved challenge keeps the WebView clear for later alerts is untested. The challenge was left on screen for the user.

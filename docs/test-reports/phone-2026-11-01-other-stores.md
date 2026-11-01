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

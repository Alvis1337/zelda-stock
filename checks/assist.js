// Assisted checkout. Runs on a retailer's product page inside the app, signed in
// as you. Adds ONE console to the cart and opens the cart; you finish checkout.
// It refuses (and just leaves the page open) unless the price is near MSRP and
// the seller is the retailer itself. It never touches captchas or queues.
(async function () {
  var R = ZSA_PARAMS.retailer, CAP = ZSA_PARAMS.maxPrice;
  var say = function (t) { try { ZSA.status(t); } catch (e) {} };
  var CART = {
    'Best Buy': 'https://www.bestbuy.com/cart',
    'Target': 'https://www.target.com/cart',
    'Walmart': 'https://www.walmart.com/cart',
    'Amazon': 'https://www.amazon.com/gp/cart/view.html',
    'Nintendo': 'https://www.nintendo.com/us/cart/'
  };
  var OWN = { 'Best Buy': /best\s*buy/i, 'Target': /target/i, 'Walmart': /walmart/i, 'Amazon': /^\s*amazon(\.com)?\b/i, 'Nintendo': /nintendo/i };
  try {
    if (!CART[R]) { say(R + ': in-store only. Use the page to reserve or check stores.'); return; }
    if (zsBlocked()) { say('Bot check on the page: solve it, then tap Add to cart.'); return; }

    // 1. Find the main buy button (Amazon has a fixed id; elsewhere the first visible one).
    var btn = null;
    for (var i = 0; i < 24 && !btn; i++) {
      btn = (R === 'Amazon' && document.querySelector('#add-to-cart-button')) ||
            (R === 'Best Buy' && document.querySelector('[data-sku-id="6691841"] button[data-button-state="ADD_TO_CART"], button[data-sku-id="6691841"][data-button-state="ADD_TO_CART"]')) ||
            zsFindButton(/^(add to cart|add for (shipping|pickup|delivery))$/i);
      if (btn && zsDisabled(btn)) btn = null;
      if (!btn) await zsSleep(500);
    }
    if (!btn) { say('No active Add to cart button. It may have sold out.'); return; }

    // 2. Read the buy area: from the product title down to the button.
    var h1 = document.querySelector('h1, #productTitle');
    var area = '';
    try {
      var rg = document.createRange();
      rg.setStartBefore(h1 || document.body.firstChild);
      rg.setEndAfter(btn);
      area = rg.cloneContents().textContent || '';
    } catch (e) { area = ''; }
    if (!area) { say('Could not read the price. Not adding automatically; check the page.'); return; }

    // 3. Price guard: every dollar amount in the buy area (not monthly payments).
    var prices = [];
    area.replace(/\$\s*([\d,]{2,7})(?:\s*\.?\s*(\d{2}))?(?!\s*\/\s*mo)/gi, function (m, d, c) {
      prices.push(parseFloat(d.replace(/,/g, '') + '.' + (c || '00')));
      return m;
    });
    var top = prices.length ? Math.max.apply(null, prices) : null;
    if (top === null) { say('Could not read the price. Not adding automatically; check the page.'); return; }
    if (top > CAP) { say('Price shows $' + top.toFixed(2) + ' (over $' + CAP + '). Not adding: likely a reseller.'); return; }

    // 4. Seller guard: if the page names a seller, it must be the retailer itself.
    var sm = area.match(/sold\s*(?:&|and)?\s*(?:shipped\s*)?by\s*:?\s*([A-Za-z0-9 .,'&-]{2,40})/i);
    if (!sm && R === 'Amazon') {
      var mi = document.querySelector('#merchantInfoFeature_feature_div .offer-display-feature-text, #sellerProfileTriggerId');
      if (mi) sm = [null, mi.textContent.trim()];
    }
    if (sm && !OWN[R].test(sm[1])) { say('Sold by "' + sm[1].trim() + '", not ' + R + '. Not adding.'); return; }
    if (R === 'Amazon' && !sm) { say('Could not confirm Amazon is the seller. Not adding; check the page.'); return; }

    // 5. Quantity 1, add, then go to the cart.
    var qty = document.querySelector('select[name="quantity"], #quantity');
    if (qty && qty.value && qty.value !== '1') { qty.value = '1'; qty.dispatchEvent(new Event('change', { bubbles: true })); }
    say('Adding to cart ($' + top.toFixed(2) + ')…');
    btn.click();
    await zsSleep(3500);
    if (zsBlocked()) { say('Bot check or queue after Add to cart: finish it, then open the cart.'); return; }
    say('Added. Opening cart: check out from here.');
    location.href = CART[R];
  } catch (e) {
    say('Assist stopped: ' + e);
  }
})();

// Assisted checkout. Runs on a retailer's product page inside the app, signed in
// as you. Adds ONE console to the cart and opens the cart; you finish checkout.
// It refuses (and just leaves the page open) unless the price is near MSRP and
// the seller is the retailer itself. It never touches captchas or queues.
(async function () {
  var R = ZSA_PARAMS.retailer, CAP = ZSA_PARAMS.maxPrice;
  // The app polls window.__zsaStatus (no JS bridge object for sites to spot); ZSA is the old bridge.
  var say = function (t) { window.__zsaStatus = t; try { ZSA.status(t); } catch (e) {} };
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

    // 2. Read the buy area: from the product title to the button, whichever comes first.
    // Only text a screen reader would read: Best Buy and Walmart draw the price twice,
    // once as "$22.99" and once as aria-hidden "$" "22" "99", which joined reads $2299.
    var h1 = Array.prototype.find.call(document.querySelectorAll('h1, #productTitle'), function (h) { return h.offsetParent !== null; });
    var textOf = function (root, range) {
      var parts = [], w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), n;
      while ((n = w.nextNode())) {
        if (range && !range.intersectsNode(n)) continue;
        var el = n.parentElement;
        if (!el || el.closest('[aria-hidden="true"], [hidden], script, style, noscript')) continue;
        parts.push(n.nodeValue);
      }
      return parts.join(' ').replace(/\s+/g, ' ');
    };
    var area = '';
    try {
      var rg = document.createRange();
      var first = h1 && (h1.compareDocumentPosition(btn) & Node.DOCUMENT_POSITION_PRECEDING) ? btn : h1;
      rg.setStartBefore(first || document.body.firstChild);
      rg.setEndAfter(first === btn ? h1 : btn);
      area = textOf(rg.commonAncestorContainer, rg);
    } catch (e) { area = ''; }
    var spread = area.length;
    var PRICE = /\$\s*(\d{1,3}(?:,\d{3})+|\d+)(?:\s*\.\s*(\d{2})|\s+(\d{2}))?(?!\d)(?!\s*\/\s*mo)/gi;
    // Financing lines quote other amounts ("orders over $50", "4 payments of $130.00").
    var FINANCE = /(payments? of|orders? (?:over|of|above)|over|per month|monthly)\s*\$\s*[\d,]+(?:\.\d{2})?/gi;
    var priceText = area.replace(FINANCE, ' ');
    // No price between title and button (Target draws it elsewhere): read the store's own
    // price element. Never just "some block near the button": at Target that's the financing box.
    // Target leaves its price box empty until it's scrolled into view, so scroll to it and wait.
    if (!PRICE.test(priceText)) {
      priceText = '';
      var PRICE_EL = '[data-test="module-product-detail-price-v2"], [data-test="product-price"], ' +
        '[data-testid="price-wrap"], [itemprop="price"], .priceView-hero-price, [data-testid="customer-price"], ' +
        '#corePrice_feature_div, #corePriceDisplay_desktop_feature_div';
      var box = document.querySelector(PRICE_EL);
      if (box && box.scrollIntoView) box.scrollIntoView({ block: 'center' });
      for (var w = 0; w < 10 && !priceText; w++) {
        var pel = Array.prototype.find.call(document.querySelectorAll(PRICE_EL), function (e) {
          return e.offsetParent !== null && /\$\s*\d/.test(textOf(e));
        });
        if (pel) priceText = textOf(pel).replace(FINANCE, ' ');
        else await zsSleep(400);
      }
    }
    PRICE.lastIndex = 0;
    if (!area && !priceText) { say('Could not read the price. Not adding automatically; check the page.'); return; }
    // When the product is sold out, the first Add to cart on the page belongs to another
    // product (a carousel). Walmart names the product in the button's label; elsewhere a
    // long stretch of page between the title and the button gives it away.
    var norm = function (x) { return (x || '').toLowerCase().replace(/[^a-z0-9]+/g, ''); };
    var forName = (btn.getAttribute('aria-label') || '').match(/^\s*add to cart\s*[-–:]\s*(.+)$/i);
    if ((forName && h1 && norm(forName[1]).slice(0, 24) !== norm(h1.innerText).slice(0, 24)) || spread > 4000) {
      say('The Add to cart button is for another product; this one looks sold out. Not adding.'); return;
    }

    // 3. Price guard: every dollar amount in the price text (not financing lines).
    var prices = [];
    priceText.replace(PRICE, function (m, d, c1, c2) {
      prices.push(parseFloat(d.replace(/,/g, '') + '.' + (c1 || c2 || '00')));
      return m;
    });
    // Last resort: the price the page declares for search engines.
    if (!prices.length) {
      var meta = document.querySelector('[itemprop="price"][content], meta[property="product:price:amount"]');
      var mp = meta && parseFloat(String(meta.getAttribute('content')).replace(/[^\d.]/g, ''));
      if (mp) prices.push(mp);
    }
    var top = prices.length ? Math.max.apply(null, prices) : null;
    if (top === null) { say('Could not read the price. Not adding automatically; check the page.'); return; }
    if (top > CAP) { say('Price shows $' + top.toFixed(2) + ' (over $' + CAP + '). Not adding: likely a reseller.'); return; }

    // 4. Seller guard: if the page names a seller, it must be the retailer itself.
    var sm = area.match(/sold\s*(?:&|and)?\s*(?:shipped\s*)?by\s*:?\s*([A-Za-z0-9 .,'&-]{2,40})/i);
    if (!sm && R === 'Amazon') {
      var mi = document.querySelector('#merchantInfoFeature_feature_div .offer-display-feature-text, #sellerProfileTriggerId');
      if (mi) sm = [null, mi.textContent.trim()];
    }
    if (sm && !OWN[R].test(sm[1])) { say('Sold by "' + sm[1].replace(/\s+\d.*$/, '').trim() + '", not ' + R + '. Not adding.'); return; }
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

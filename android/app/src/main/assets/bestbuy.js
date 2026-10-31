// Runs on https://www.bestbuy.com/site/searchpage.jsp?st=... inside the app's WebView.
// Best Buy sells everything itself on bestbuy.com, so no seller filter is needed
// beyond skipping open-box / refurbished listings (handled by zsIsConsole).
(async function () {
  var P = ZS_PARAMS, rows = [];
  try {
    if (zsBlocked()) return zsFinish({ blocked: true, rows: [] });
    // 1. Find the console on the search results page.
    var items = {};
    for (var i = 0; i < 20; i++) {
      document.querySelectorAll('[data-sku-id], li.sku-item, .product-list-item').forEach(function (card) {
        var sku = card.getAttribute('data-sku-id');
        var a = card.querySelector('h4 a, .sku-title a, a.product-list-item-link, a[href*="/site/"]');
        if (!a) return;
        if (!sku) { var m = a.href.match(/skuId=(\d+)|\/(\d{7})\.p/); sku = m && (m[1] || m[2]); }
        var title = (a.innerText || a.getAttribute('aria-label') || '').trim();
        if (!sku || !zsIsConsole(title) || items[sku]) return;
        var btn = card.querySelector('[data-button-state], button.add-to-cart-button, button[data-test-id*="add-to-cart"]');
        items[sku] = {
          title: title.split('\n')[0],
          url: a.href.split('#')[0],
          state: btn ? (btn.getAttribute('data-button-state') || '') : '',
          btnText: btn ? (btn.innerText || '').trim() : '',
          disabled: btn ? (btn.disabled || btn.getAttribute('aria-disabled') === 'true') : null
        };
      });
      if (Object.keys(items).length && i > 4) break;
      await zsSleep(750);
    }
    var skus = Object.keys(items);
    if (!skus.length) {
      rows.push(zsRow('Best Buy', 'search', 'online', 'NONE FOUND', 'no Zelda 40th Anniversary console on the search page', location.href));
      return zsFinish({ rows: rows });
    }
    // 2. Online button state, as the site shows it.
    skus.forEach(function (s) {
      var it = items[s], st = (it.state || '').toUpperCase(), t = it.btnText.toLowerCase();
      var label = it.state || it.btnText || 'no button';
      if (st === 'ADD_TO_CART' || (/add to cart/.test(t) && it.disabled === false))
        rows.push(zsRow('Best Buy', it.title, 'online', 'IN STOCK', 'Add to Cart is live (SKU ' + s + ')', it.url));
      else if (/SOLD_OUT|UNAVAILABLE/.test(st) || /sold out|unavailable/.test(t))
        rows.push(zsRow('Best Buy', it.title, 'online', 'OUT OF STOCK', label + ' (SKU ' + s + ')', it.url));
      else
        rows.push(zsRow('Best Buy', it.title, 'online', 'UNVERIFIED', label + ' (SKU ' + s + ')', it.url));
    });
    // 3. Store pickup near the ZIP, through the pickup API bestbuy.com uses.
    for (var s of skus) {
      var it = items[s], z = P.zips[0];
      try {
        var r = await fetch('/fulfillment/ispu/api/ispu/v2', {
          method: 'POST', credentials: 'include',
          headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
          body: JSON.stringify({
            zipCode: z, showOnShelf: true, lookupInStoreQuantity: true, consolidated: true,
            showOnlyOnShelf: false, showInStore: true, onlyBestBuyLocations: true,
            pickupTypes: ['UPS_ACCESS_POINT', 'FEDEX_HAL'],
            items: [{ sku: s, condition: null, quantity: 1, itemSeqNumber: '1', reservationToken: null,
                      selectedServices: [], requiredAccessories: [], isTradeIn: false, isLeased: false }]
          })
        });
        if (!r.ok) { rows.push(zsRow('Best Buy', it.title, 'stores near ' + z, 'UNVERIFIED', 'pickup lookup HTTP ' + r.status + '; check the product page', it.url)); continue; }
        var j = await r.json(), ispu = j.ispu || j;
        var names = {};
        (ispu.locations || []).forEach(function (l) {
          names[l.id] = { name: l.name || ('store ' + l.id), dist: l.distance, city: l.city };
        });
        var item = (ispu.items || [])[0];
        var locs = (item && item.locations) || null;
        if (!locs) { rows.push(zsRow('Best Buy', it.title, 'stores near ' + z, 'UNVERIFIED', 'pickup lookup returned nothing readable', it.url)); continue; }
        var hit = false, counted = 0;
        locs.forEach(function (l) {
          var n = names[l.locationId] || { name: 'store ' + l.locationId };
          var d = n.dist != null ? parseFloat(n.dist) : null;
          if (d != null && d > P.radius) return;
          counted++;
          var q = Math.max((l.availability && l.availability.availablePickupQuantity) || 0,
                           (l.inStoreAvailability && l.inStoreAvailability.availableInStoreQuantity) || 0);
          if (q > 0) {
            hit = true;
            rows.push(zsRow('Best Buy', it.title, n.name + (d != null ? ' (' + d + ' mi)' : ''), 'IN STOCK',
              'qty ' + q + ' for pickup' + (n.city ? ', ' + n.city : ''), it.url));
          }
        });
        if (!hit) rows.push(zsRow('Best Buy', it.title, counted + ' stores within ' + P.radius + ' mi of ' + z, 'OUT OF STOCK', 'no pickup quantity at any store', it.url));
      } catch (e) {
        rows.push(zsRow('Best Buy', it.title, 'stores near ' + z, 'UNVERIFIED', String(e), it.url));
      }
    }
    zsFinish({ rows: rows });
  } catch (e) {
    zsFinish({ rows: rows.concat([zsRow('Best Buy', '-', '-', 'ERROR', String(e), location.href)]) });
  }
})();

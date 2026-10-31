// Runs on the Best Buy product page for the console (SKU 6691841).
(async function () {
  var P = ZS_PARAMS, rows = [], SKU = '6691841', url = location.href.split('?')[0];
  var NAME = 'Switch 2 – The Legend of Zelda 40th Anniversary Edition';
  try {
    // 1. Online. Primary: Best Buy's own button-state API for this exact SKU.
    // Fallback: the buy button scoped to this SKU (never a carousel item's button).
    var verdict = null, detail = '', problem = null, api = null;
    try {
      var pb = await fetch('/api/3.0/priceBlocks?skus=' + SKU, { credentials: 'include', headers: { Accept: 'application/json' } });
      if (pb.ok) {
        (function walk(o, d) {
          if (api || !o || typeof o !== 'object' || d > 8) return;
          if (typeof o.buttonState === 'string') { api = o.buttonState.toUpperCase(); return; }
          Object.keys(o).forEach(function (k) { walk(o[k], d + 1); });
        })(await pb.json(), 0);
      } else { detail = 'button API HTTP ' + pb.status; }
    } catch (e) { detail = 'button API failed'; }
    if (api) {
      verdict = api === 'ADD_TO_CART' ? 'IN STOCK' : 'OUT OF STOCK';
      detail = 'Best Buy says ' + api.toLowerCase().replace(/_/g, ' ');
    }
    for (var i = 0; i < 16 && !verdict; i++) {
      problem = zsPageProblem();
      var el = document.querySelector('[data-sku-id="' + SKU + '"][data-button-state], [data-sku-id="' + SKU + '"] [data-button-state]');
      var state = el ? (el.getAttribute('data-button-state') || '').toUpperCase() : '';
      if (state === 'ADD_TO_CART') { verdict = 'IN STOCK'; detail = 'Add to Cart is live'; }
      else if (state) { verdict = 'OUT OF STOCK'; detail = 'button: ' + state.toLowerCase().replace(/_/g, ' '); }
      if (!verdict) await zsSleep(750);
    }
    if (!verdict) { verdict = 'UNVERIFIED'; detail = (problem || 'no stock signal for SKU ' + SKU) + ' on ' + zsPageLabel() + (detail ? '; ' + detail : ''); }
    rows.push(zsRow('Best Buy', NAME, 'online', verdict, detail + ' (SKU ' + SKU + ')', url));

    // 2. Store pickup near the ZIP, through the pickup API bestbuy.com uses.
    var z = P.zips[0];
    try {
      var r = await fetch('/fulfillment/ispu/api/ispu/v2', {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        body: JSON.stringify({
          zipCode: z, showOnShelf: true, lookupInStoreQuantity: true, consolidated: true,
          showOnlyOnShelf: false, showInStore: true, onlyBestBuyLocations: true,
          pickupTypes: ['UPS_ACCESS_POINT', 'FEDEX_HAL'],
          items: [{ sku: SKU, condition: null, quantity: 1, itemSeqNumber: '1', reservationToken: null,
                    selectedServices: [], requiredAccessories: [], isTradeIn: false, isLeased: false }]
        })
      });
      if (!r.ok) throw new Error('pickup lookup HTTP ' + r.status);
      var j = await r.json(), ispu = j.ispu || j, names = {};
      (ispu.locations || []).forEach(function (l) { names[l.id] = { name: l.name || ('store ' + l.id), dist: l.distance, city: l.city }; });
      var item = (ispu.items || [])[0], locs = item && item.locations;
      if (!locs) throw new Error('pickup lookup returned nothing readable');
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
          rows.push(zsRow('Best Buy', NAME, n.name + (d != null ? ' (' + d + ' mi)' : ''), 'IN STOCK', 'qty ' + q + ' for pickup' + (n.city ? ', ' + n.city : ''), url));
        }
      });
      if (!hit) rows.push(zsRow('Best Buy', NAME, counted + ' stores within ' + P.radius + ' mi of ' + z, 'OUT OF STOCK', 'no pickup quantity at any store', url));
    } catch (e) {
      rows.push(zsRow('Best Buy', NAME, 'stores near ' + z, 'UNVERIFIED', String(e.message || e) + '. Use "Check stores" on the product page.', url));
    }
    zsFinish({ rows: rows });
  } catch (e) {
    zsFinish({ rows: rows.concat([zsRow('Best Buy', NAME, '-', 'ERROR', String(e), url)]) });
  }
})();

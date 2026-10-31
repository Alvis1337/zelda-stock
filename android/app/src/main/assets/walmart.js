// Runs on https://www.walmart.com/search?q=... inside the app's WebView.
(async function () {
  var rows = [];
  try {
    var el = null;
    for (var i = 0; i < 20 && !el; i++) {
      if (zsBlocked()) return zsFinish({ blocked: true, rows: [] });
      el = document.getElementById('__NEXT_DATA__');
      if (!el) await zsSleep(750);
    }
    if (!el) return zsFinish({ rows: [zsRow('Walmart', 'search', 'online', 'UNVERIFIED', 'no product data on page', location.href)] });
    var data = JSON.parse(el.textContent), seen = {}, skipped = 0;
    (function walk(o, depth) {
      if (!o || typeof o !== 'object' || depth > 40) return;
      if (Array.isArray(o)) { o.forEach(function (x) { walk(x, depth + 1); }); return; }
      if (typeof o.name === 'string' && zsIsConsole(o.name) &&
          (o.availabilityStatusV2 || o.availabilityStatusDisplayValue) && !seen[o.name]) {
        seen[o.name] = 1;
        var st = (o.availabilityStatusV2 && (o.availabilityStatusV2.value || o.availabilityStatusV2.display)) ||
                 o.availabilityStatusDisplayValue || 'unknown';
        var price = (o.priceInfo && o.priceInfo.currentPrice && o.priceInfo.currentPrice.price) || o.price || '?';
        var seller = o.sellerName || 'unknown seller';
        var badges = [];
        (function b(x, d) {
          if (!x || typeof x !== 'object' || d > 6) return;
          if (typeof x.text === 'string' && /pickup|delivery|shipping|today|tomorrow/i.test(x.text)) badges.push(x.text);
          Object.keys(x).forEach(function (k) { b(x[k], d + 1); });
        })(o.fulfillmentBadgeGroups || o.fulfillmentBadges || null, 0);
        var up = String(st).toUpperCase().replace(/\s+/g, '_');
        var status = up === 'IN_STOCK' ? 'IN STOCK' : (/OUT/.test(up) ? 'OUT OF STOCK' : 'UNVERIFIED');
        // Walmart's own listings only: skip marketplace sellers (scalpers).
        if (!/^walmart/i.test(seller)) { skipped++; return; }
        var url = o.canonicalUrl ? 'https://www.walmart.com' + o.canonicalUrl : location.href;
        rows.push(zsRow('Walmart', o.name, 'online', status,
          st + ', $' + price + ', sold by ' + seller +
          (badges.length ? ' | ' + badges.join(', ') : ''), url));
        return;
      }
      Object.keys(o).forEach(function (k) { walk(o[k], depth + 1); });
    })(data, 0);
    if (!rows.length) rows.push(zsRow('Walmart', 'search', 'online', 'NONE FOUND', 'no Zelda 40th Anniversary console sold by Walmart' + (skipped ? ' (' + skipped + ' marketplace listings hidden)' : ''), location.href));
    zsFinish({ rows: rows });
  } catch (e) {
    zsFinish({ rows: rows.concat([zsRow('Walmart', '-', '-', 'ERROR', String(e), location.href)]) });
  }
})();

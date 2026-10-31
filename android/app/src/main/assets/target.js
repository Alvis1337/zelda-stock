// Runs on https://www.target.com/s?searchTerm=... inside the app's WebView.
(async function () {
  var P = ZS_PARAMS, rows = [], KEY = '9f36aeafbe60771e321a7cc95a78140772ab3e96';
  try {
    if (zsBlocked()) return zsFinish({ blocked: true, rows: [] });
    // 1. Find Zelda Switch 2 products on the search page (page renders with JS, so poll).
    var items = {};
    for (var i = 0; i < 20; i++) {
      document.querySelectorAll('a[href*="/A-"]').forEach(function (a) {
        var m = a.getAttribute('href').match(/\/A-(\d+)/);
        var title = (a.getAttribute('aria-label') || a.innerText || '').trim();
        if (m && zsIsConsole(title) && !items[m[1]]) {
          var card = a.closest('[data-test*="product"], li, section') || a.parentElement;
          var ctext = card ? card.innerText : '';
          // Skip Target Plus marketplace listings (sold by another seller).
          var sold = ctext.match(/sold (?:and shipped )?by ([^\n]+)/i);
          if (sold && !/target/i.test(sold[1])) return;
          items[m[1]] = { title: title.split('\n')[0], card: ctext };
        }
      });
      if (Object.keys(items).length && i > 4) break;
      await zsSleep(750);
    }
    var tcins = Object.keys(items);
    if (!tcins.length) {
      rows.push(zsRow('Target', 'search', 'online', 'NONE FOUND', 'no Zelda 40th Anniversary console sold by Target on the search page', location.href));
      return zsFinish({ rows: rows });
    }
    // 2. Online signal straight from the product card text.
    tcins.forEach(function (t) {
      var it = items[t], url = 'https://www.target.com/p/-/A-' + t, c = it.card.toLowerCase();
      if (/sold out|out of stock/.test(c)) rows.push(zsRow('Target', it.title, 'online', 'OUT OF STOCK', 'card says sold out / out of stock', url));
      else if (/add to cart/.test(c)) rows.push(zsRow('Target', it.title, 'online', 'IN STOCK', 'card shows Add to cart' + (/pre-?order/.test(c) ? ' (preorder)' : ''), url));
      else if (c) rows.push(zsRow('Target', it.title, 'online', 'UNVERIFIED', 'card shows no stock wording', url));
    });
    // 3. Store stock near each ZIP, using the same API target.com calls.
    for (var t of tcins) {
      for (var z of P.zips) {
        var url = 'https://redsky.target.com/redsky_aggregations/v1/web/fiats_v1?key=' + KEY +
          '&tcin=' + t + '&nearby=' + z + '&radius=' + P.radius +
          '&limit=20&include_only_available_stores=false&requested_quantity=1';
        var purl = 'https://www.target.com/p/-/A-' + t, title = items[t].title;
        try {
          var r = await fetch(url, { credentials: 'include' });
          if (!r.ok) { rows.push(zsRow('Target', title, 'stores near ' + z, 'UNVERIFIED', 'HTTP ' + r.status, purl)); continue; }
          var j = await r.json();
          var f = j && j.data && j.data.fulfillment_fiats;
          var locs = (f && f.locations) || null;
          if (!locs) { rows.push(zsRow('Target', title, 'stores near ' + z, 'UNVERIFIED', 'unexpected response', purl)); continue; }
          var hit = false;
          locs.forEach(function (l) {
            var q = l.location_available_to_promise_quantity || 0;
            var s = l.store || {}, a = s.mailing_address || {};
            if (q > 0) {
              hit = true;
              rows.push(zsRow('Target', title, (s.location_name || l.location_id) + ' (' + l.distance + ' mi)', 'IN STOCK',
                'qty ' + Math.floor(q) + (a.address_line1 ? ', ' + a.address_line1 + ', ' + (a.city || '') : ''), purl));
            }
          });
          if (!hit) rows.push(zsRow('Target', title, locs.length + ' stores within ' + P.radius + ' mi of ' + z, 'OUT OF STOCK', '0 available at every store', purl));
        } catch (e) {
          rows.push(zsRow('Target', title, 'stores near ' + z, 'UNVERIFIED', String(e), purl));
        }
      }
    }
    zsFinish({ rows: rows });
  } catch (e) {
    zsFinish({ rows: rows.concat([zsRow('Target', '-', '-', 'ERROR', String(e), location.href)]) });
  }
})();

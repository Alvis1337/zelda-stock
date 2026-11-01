// Runs on https://www.target.com/s?searchTerm=... in the app (WebView) or on the server (Chromium).
(async function () {
  var P = ZS_PARAMS, rows = [], KEY = '9f36aeafbe60771e321a7cc95a78140772ab3e96';
  try {
    if (zsBlocked()) return zsFinish({ blocked: true, rows: [] });
    // 1. Find Zelda Switch 2 products on the search page (page renders with JS, so poll).
    // A product can show more than once (sponsored slot + organic result); keep every card.
    var items = {};
    var tcinOf = function (x) { var o = (x.getAttribute('href') || '').match(/\/A-(\d+)/); return o && o[1]; };
    var carts = function (el) {
      return Array.prototype.filter.call(el.querySelectorAll('button'), function (b) { return /add to cart/i.test(b.innerText || ''); }).length;
    };
    for (var i = 0; i < 20; i++) {
      document.querySelectorAll('a[href*="/A-"]').forEach(function (a) {
        var t = tcinOf(a);
        var title = (a.getAttribute('aria-label') || a.innerText || '').trim();
        if (!t || !zsIsConsole(title)) return;
        // The card is the largest ancestor that still holds only this product: no other
        // product's link and at most one Add to cart button (sponsored slots link elsewhere).
        // closest() can stop at the image wrapper, whose text is empty.
        var card = a;
        while (card.parentElement && card.parentElement !== document.body &&
               !Array.prototype.some.call(card.parentElement.querySelectorAll('a[href*="/A-"]'), function (x) {
                 var o = tcinOf(x); return o && o !== t;
               }) && carts(card.parentElement) <= 1) card = card.parentElement;
        var it = items[t] || (items[t] = { title: title.split('\n')[0], cards: [] });
        if (it.cards.indexOf(card) < 0) it.cards.push(card);
      });
      if (Object.keys(items).length && i > 4) break;
      await zsSleep(750);
    }
    // Read the cards only now: stock wording renders after the link does.
    await zsSleep(1500);
    Object.keys(items).forEach(function (t) {
      var texts = items[t].cards.filter(function (c) { return c.isConnected; })
        .map(function (c) { return (c.innerText || '').toLowerCase(); }).filter(Boolean);
      // Skip Target Plus marketplace listings (sold by another seller).
      var sold = texts.join('\n').match(/sold (?:and shipped )?by ([^\n]+)/i);
      if (sold && !/target/i.test(sold[1])) delete items[t];
      else items[t].texts = texts;
    });
    var tcins = Object.keys(items);
    if (!tcins.length) {
      var pr = zsPageProblem();
      if (pr) return zsFinish({ rows: [zsRow('Target', 'search', 'online', 'UNVERIFIED', pr + ' on ' + zsPageLabel(), location.href)] });
      rows.push(zsRow('Target', 'search', 'online', 'NONE FOUND', 'no Zelda 40th Anniversary console sold by Target on the search page', location.href));
      return zsFinish({ rows: rows });
    }
    // 2. Online signal from the product card text. Out-of-stock wording on any card wins;
    // in stock needs a card with exactly one price and one Add to cart (so it is this product's).
    tcins.forEach(function (t) {
      var it = items[t], url = 'https://www.target.com/p/-/A-' + t;
      var count = function (c, re) { return (c.match(re) || []).length; };
      if (it.texts.some(function (c) { return /sold out|out of stock/.test(c); })) {
        rows.push(zsRow('Target', it.title, 'online', 'OUT OF STOCK', 'card says sold out / out of stock', url));
      } else {
        var own = it.texts.filter(function (c) { return count(c, /add to cart/g) === 1 && count(c, /\$\d[\d,]*\.\d\d/g) <= 2; });
        if (own.length) rows.push(zsRow('Target', it.title, 'online', 'IN STOCK', 'card shows Add to cart' + (own.some(function (c) { return /pre-?order/.test(c); }) ? ' (preorder)' : ''), url));
        else if (it.texts.length) rows.push(zsRow('Target', it.title, 'online', 'UNVERIFIED', 'card shows no stock wording', url));
      }
    });
    // 3. Store stock near each ZIP, using the same API target.com calls.
    for (var t of tcins) {
      for (var z of P.zips) {
        var url = 'https://redsky.target.com/redsky_aggregations/v1/web/fiats_v1?key=' + KEY +
          '&tcin=' + t + '&nearby=' + z + '&radius=' + P.radius +
          '&limit=20&include_only_available_stores=false&requested_quantity=1';
        var purl = 'https://www.target.com/p/-/A-' + t, title = items[t].title;
        try {
          // No cookies: redsky rate-limits per shopper session (HTTP 435) and doesn't need it.
          var r = await fetch(url, { credentials: 'omit' });
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
          if (!hit) rows.push(zsRow('Target', title, locs.length + (locs.length === 1 ? ' store' : ' stores') + ' within ' + P.radius + ' mi of ' + z, 'OUT OF STOCK', '0 available at every store', purl));
        } catch (e) {
          rows.push(zsRow('Target', title, 'stores near ' + z, 'UNVERIFIED', String(e), purl));
        }
        await zsSleep(1000);
      }
    }
    zsFinish({ rows: rows });
  } catch (e) {
    zsFinish({ rows: rows.concat([zsRow('Target', '-', '-', 'ERROR', String(e), location.href)]) });
  }
})();

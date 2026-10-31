// Runs on https://www.gamestop.com/search/?q=... inside the app's WebView.
(async function () {
  var rows = [];
  try {
    if (zsBlocked()) return zsFinish({ blocked: true, rows: [] });
    var links = {};
    for (var i = 0; i < 16; i++) {
      document.querySelectorAll('a[href*="/products/"]').forEach(function (a) {
        var t = (a.getAttribute('aria-label') || a.innerText || a.getAttribute('title') || '').trim();
        if (zsIsConsole(t) || zsIsConsole(a.href.replace(/-/g, ' '))) links[a.href.split('?')[0]] = t.split('\n')[0] || a.href;
      });
      if (Object.keys(links).length && i > 4) break;
      await zsSleep(750);
    }
    if (!Object.keys(links).length) {
      rows.push(zsRow('GameStop', 'search', 'online', 'NONE FOUND', 'no Zelda 40th Anniversary console in search results', location.href));
    }
    for (var url of Object.keys(links).slice(0, 10)) {
      var name = links[url];
      try {
        var r = await fetch(url, { credentials: 'include' });
        if (!r.ok) { rows.push(zsRow('GameStop', name, 'online', 'UNVERIFIED', 'HTTP ' + r.status, url)); continue; }
        var doc = new DOMParser().parseFromString(await r.text(), 'text/html');
        var av = null, price = null, title = null;
        doc.querySelectorAll('script[type="application/ld+json"]').forEach(function (s) {
          try {
            var d = JSON.parse(s.textContent);
            (Array.isArray(d) ? d : [d]).forEach(function (it) {
              if (!it || !it.offers) return;
              var offs = Array.isArray(it.offers) ? it.offers : [it.offers];
              offs.forEach(function (o) {
                if (o.availability && !av) { av = String(o.availability).split('/').pop(); price = o.price; title = it.name; }
              });
            });
          } catch (e) {}
        });
        name = title || name;
        if (!zsIsConsole(name)) continue;
        if (av === 'InStock') rows.push(zsRow('GameStop', name, 'online', 'IN STOCK', '$' + price, url));
        else if (/OutOfStock|SoldOut|Discontinued/.test(av || '')) rows.push(zsRow('GameStop', name, 'online', 'OUT OF STOCK', '$' + price, url));
        else if (av) rows.push(zsRow('GameStop', name, 'online', 'UNVERIFIED', av + ', $' + price, url));
        else rows.push(zsRow('GameStop', name, 'online', 'UNVERIFIED', 'page has no availability data', url));
      } catch (e) {
        rows.push(zsRow('GameStop', name, 'online', 'UNVERIFIED', String(e), url));
      }
    }
    rows.push(zsRow('GameStop', '-', 'store pickup', 'MANUAL', "open a product and tap 'Check other stores'", 'https://www.gamestop.com/search/?q=zelda+switch+2'));
    zsFinish({ rows: rows });
  } catch (e) {
    zsFinish({ rows: rows.concat([zsRow('GameStop', '-', '-', 'ERROR', String(e), location.href)]) });
  }
})();

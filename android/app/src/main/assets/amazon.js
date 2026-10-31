// Runs on https://www.amazon.com/s?k=... inside the app's WebView.
// Only listings shipped and sold by Amazon.com count; marketplace sellers are hidden.
(async function () {
  var rows = [];
  try {
    if (zsBlocked() || document.querySelector('form[action*="validateCaptcha"]')) return zsFinish({ blocked: true, rows: [] });
    var items = {};
    for (var i = 0; i < 16; i++) {
      document.querySelectorAll('[data-component-type="s-search-result"][data-asin]').forEach(function (el) {
        var asin = el.getAttribute('data-asin');
        var t = el.querySelector('h2');
        var title = t ? t.innerText.trim() : '';
        if (asin && zsIsConsole(title) && !items[asin]) items[asin] = title;
      });
      if (Object.keys(items).length && i > 3) break;
      await zsSleep(750);
    }
    var asins = Object.keys(items).slice(0, 6), hidden = 0;
    for (var a of asins) {
      var url = 'https://www.amazon.com/dp/' + a;
      try {
        var r = await fetch(url, { credentials: 'include' });
        var doc = new DOMParser().parseFromString(await r.text(), 'text/html');
        if (doc.querySelector('form[action*="validateCaptcha"]')) { rows.push(zsRow('Amazon', items[a], 'online', 'UNVERIFIED', 'bot check on product page', url)); continue; }
        var txt = function (sel) { var e = doc.querySelector(sel); return e ? e.textContent.replace(/\s+/g, ' ').trim() : ''; };
        // Seller name only (not "Ships from"): a marketplace seller can ship from Amazon.
        var seller = txt('#merchantInfoFeature_feature_div .offer-display-feature-text') || txt('#sellerProfileTriggerId');
        if (!seller) {
          var m = (txt('#tabular-buybox') + ' ' + txt('#merchant-info')).match(/sold by\s+(.+?)(?:\s+and\b|\s+returns\b|\s+payment\b|\.|$)/i);
          seller = m ? m[1].trim() : '';
        }
        var avail = txt('#availability');
        var cart = !!doc.querySelector('#add-to-cart-button, #buy-now-button');
        var price = txt('#corePrice_feature_div .a-offscreen') || txt('.a-price .a-offscreen');
        var byAmazon = /^amazon(\.com)?\b/i.test(seller);
        if (!seller && /unavailable|out of stock/i.test(avail)) {
          rows.push(zsRow('Amazon', items[a], 'online', 'OUT OF STOCK', avail + ' (no seller listed)', url));
          continue;
        }
        if (!byAmazon) { if (seller || cart) hidden++; continue; }
        var status = cart && !/unavailable|out of stock/i.test(avail) ? 'IN STOCK'
                   : (/unavailable|out of stock/i.test(avail) || !cart ? 'OUT OF STOCK' : 'UNVERIFIED');
        rows.push(zsRow('Amazon', items[a], 'online', status, (avail || 'no availability text') + (price ? ', ' + price : '') + ', sold by Amazon', url));
      } catch (e) {
        rows.push(zsRow('Amazon', items[a], 'online', 'UNVERIFIED', String(e), url));
      }
    }
    if (!rows.length) rows.push(zsRow('Amazon', 'search', 'online', 'NONE FOUND',
      'no Zelda 40th Anniversary console sold by Amazon' + (hidden ? ' (' + hidden + ' marketplace listings hidden)' : ''), location.href));
    zsFinish({ rows: rows });
  } catch (e) {
    zsFinish({ rows: rows.concat([zsRow('Amazon', '-', '-', 'ERROR', String(e), location.href)]) });
  }
})();

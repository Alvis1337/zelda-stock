// Runs on the Walmart product page for the console (item 21002656445).
// Counts only Walmart's own offer; a marketplace seller in the buy box is a scalper.
(async function () {
  var ITEM = '21002656445', url = 'https://www.walmart.com/ip/' + ITEM;
  var NAME = 'Nintendo Switch 2 – The Legend of Zelda 40th Anniversary Edition';
  try {
    var el = null, problem = null;
    for (var i = 0; i < 20 && !el; i++) {
      problem = zsPageProblem();
      if (problem === 'bot check') return zsFinish({ blocked: true, rows: [] });
      el = document.getElementById('__NEXT_DATA__');
      if (!el) await zsSleep(750);
    }
    if (!el) return zsFinish({ rows: [zsRow('Walmart', NAME, 'online', 'UNVERIFIED', (problem || 'no product data') + ' on ' + zsPageLabel(), url)] });
    var data = JSON.parse(el.textContent), prod = null;
    try { prod = data.props.pageProps.initialData.data.product; } catch (e) {}
    if (!prod || !prod.availabilityStatus) {
      (function walk(o, d) {
        if ((prod && prod.availabilityStatus) || !o || typeof o !== 'object' || d > 40) return;
        if (String(o.usItemId) === ITEM && o.availabilityStatus) { prod = o; return; }
        Object.keys(o).forEach(function (k) { walk(o[k], d + 1); });
      })(data, 0);
    }
    if (!prod || !prod.availabilityStatus)
      return zsFinish({ rows: [zsRow('Walmart', NAME, 'online', 'UNVERIFIED', 'could not find the product data on ' + zsPageLabel(), url)] });
    var st = String(prod.availabilityStatus).toUpperCase();
    var seller = prod.sellerName || prod.sellerDisplayName || 'unknown seller';
    var price = (prod.priceInfo && prod.priceInfo.currentPrice && prod.priceInfo.currentPrice.price) || '?';
    var walmart = /^walmart/i.test(seller);
    var rows = [];
    if (st === 'IN_STOCK' && walmart) rows.push(zsRow('Walmart', prod.name || NAME, 'online', 'IN STOCK', 'sold by ' + seller + ', $' + price, url));
    else if (st === 'IN_STOCK') rows.push(zsRow('Walmart', prod.name || NAME, 'online', 'OUT OF STOCK', 'Walmart itself is sold out; only marketplace seller ' + seller + ' at $' + price, url));
    else rows.push(zsRow('Walmart', prod.name || NAME, 'online', 'OUT OF STOCK', st.toLowerCase().replace(/_/g, ' ') + (walmart ? '' : ' (buy box: ' + seller + ')'), url));
    zsFinish({ rows: rows });
  } catch (e) {
    zsFinish({ rows: [zsRow('Walmart', NAME, '-', 'ERROR', String(e), url)] });
  }
})();

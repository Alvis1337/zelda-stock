// Runs on Best Buy's own listing for the console: /site/6691841.p?skuId=6691841.
// Reads what the page shows a shopper: which SKU and seller is displayed, the main
// buy button, and the Pickup tile. Marketplace offers never count as in stock.
(async function () {
  var SKU = '6691841', url = 'https://www.bestbuy.com/site/' + SKU + '.p?skuId=' + SKU;
  var NAME = 'Switch 2 – The Legend of Zelda 40th Anniversary Edition';
  var rows = [];
  var first = function (els) {
    els = els.filter(Boolean);
    els.sort(function (a, b) { return a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1; });
    return els[0] || null;
  };
  try {
    var text = '', btn = null, problem = null;
    for (var i = 0; i < 24; i++) {
      problem = zsPageProblem();
      if (problem === 'bot check') return zsFinish({ blocked: true, rows: [] });
      text = document.body ? document.body.innerText : '';
      // The main buy button is the first one on the page; carousels come later.
      btn = first([zsFindButton(/^add to cart$/i), zsFindButton(/^(sold out|coming soon|check stores|unavailable[\s\S]*|get notified|notify me)$/i)]);
      if (btn && /SKU/i.test(text)) break;
      await zsSleep(750);
    }
    if (!btn) return zsFinish({ rows: [zsRow('Best Buy', NAME, 'online', 'UNVERIFIED', (problem || 'no buy button') + ' on ' + zsPageLabel(), url)] });

    var shown = (text.match(/SKU:?\s*(\d{6,9})/i) || [])[1];
    var seller = (text.match(/sold\s*(?:&|and)\s*shipped\s*by\s*([^\n]+)/i) || [])[1];
    var price = (text.match(/\$\s*([\d,]{3,6})\s*\.?\s*(\d{2})?/) || []);
    var priceStr = price[1] ? '$' + price[1] + '.' + (price[2] || '00') : '';
    var bText = (btn.innerText || '').trim();

    if ((shown && shown !== SKU) || (seller && !/best\s*buy/i.test(seller))) {
      rows.push(zsRow('Best Buy', NAME, 'online', 'OUT OF STOCK',
        'Best Buy itself is sold out; page shows marketplace seller ' + (seller ? seller.trim() : 'SKU ' + shown) + (priceStr ? ' at ' + priceStr : ''), url));
      return zsFinish({ rows: rows });
    }
    if (/^add to cart$/i.test(bText) && !zsDisabled(btn)) {
      rows.push(zsRow('Best Buy', NAME, 'online', 'IN STOCK', 'Add to cart is live' + (priceStr ? ', ' + priceStr : '') + ' (SKU ' + SKU + ')', url));
    } else {
      rows.push(zsRow('Best Buy', NAME, 'online', 'OUT OF STOCK', 'button: ' + (bText || 'disabled') + ' (SKU ' + SKU + ')', url));
    }

    // Pickup tile: Best Buy fills it in for the store it picked for you.
    var pm = text.match(/\bPickup\b\s*\n+([^\n]{2,80})(?:\n+([^\n]{2,80}))?/);
    if (pm) {
      var p = (pm[1] + ' ' + (pm[2] || '')).trim();
      if (/unavailable|not available|sold out/i.test(p)) rows.push(zsRow('Best Buy', NAME, 'store pickup', 'OUT OF STOCK', 'Pickup: ' + p, url));
      else if (/ready|today|tomorrow|pick up (at|in)|available at/i.test(p)) rows.push(zsRow('Best Buy', NAME, 'store pickup', 'IN STOCK', 'Pickup: ' + p, url));
    }
    zsFinish({ rows: rows });
  } catch (e) {
    zsFinish({ rows: rows.concat([zsRow('Best Buy', NAME, '-', 'ERROR', String(e), url)]) });
  }
})();

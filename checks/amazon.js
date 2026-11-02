// Runs on the Amazon product page for the console (ASIN B0HJ6F8L6V).
// Counts only an offer sold by Amazon.com; marketplace sellers are scalpers.
(async function () {
  var ASIN = 'B0HJ6F8L6V', url = 'https://www.amazon.com/dp/' + ASIN;
  var NAME = 'Nintendo Switch 2 – The Legend of Zelda 40th Anniversary Edition';
  var txt = function (sel) { var e = document.querySelector(sel); return e ? e.textContent.replace(/\s+/g, ' ').trim() : ''; };
  try {
    var title = '', problem = null;
    for (var i = 0; i < 20 && !title; i++) {
      problem = zsPageProblem();
      if (document.querySelector('form[action*="validateCaptcha"]')) problem = 'bot check';
      if (problem === 'bot check') return zsFinish({ blocked: true, rows: [] });
      title = txt('#productTitle') || txt('#title');
      if (!title) await zsSleep(750);
    }
    if (!title) return zsFinish({ rows: [zsRow('Amazon', NAME, 'online', 'UNVERIFIED', (problem || 'product page did not load') + ' on ' + zsPageLabel(), url)] });
    var avail = txt('#availability') || txt('#outOfStock') || '';
    var cart = document.querySelector('#add-to-cart-button, #buy-now-button, input[name="submit.add-to-cart"]');
    var seller = txt('#merchantInfoFeature_feature_div .offer-display-feature-text-message') ||
                 txt('#merchantInfoFeature_feature_div .offer-display-feature-text') || txt('#sellerProfileTriggerId');
    if (!seller) {
      var m = (txt('#tabular-buybox') + ' ' + txt('#merchant-info') + ' ' + txt('#offer-display-features')).match(/sold by\s+(.+?)(?:\s+and\b|\s+returns\b|\s+payment\b|\.|$)/i);
      seller = m ? m[1].trim() : '';
    }
    var price = txt('#corePrice_feature_div .a-offscreen') || txt('#apex_desktop .a-offscreen') || txt('.a-price .a-offscreen');
    // Amazon Resale (formerly Warehouse) is Amazon selling used and returned units.
    var byAmazon = /^amazon(\.com)?\b/i.test(seller) && !/resale|warehouse/i.test(seller);
    var row;
    if (!cart) row = zsRow('Amazon', NAME, 'online', 'OUT OF STOCK', (avail || 'no Add to Cart button'), url);
    else if (byAmazon && !/unavailable|out of stock/i.test(avail)) row = zsRow('Amazon', NAME, 'online', 'IN STOCK', 'sold by Amazon' + (price ? ', ' + price : ''), url);
    else if (seller && !byAmazon) row = zsRow('Amazon', NAME, 'online', 'OUT OF STOCK', 'Amazon itself is sold out; buy box is ' + seller + (price ? ' at ' + price : ''), url);
    else row = zsRow('Amazon', NAME, 'online', 'UNVERIFIED', 'Add to Cart shown but seller unreadable' + (price ? ', ' + price : ''), url);
    zsFinish({ rows: [row] });
  } catch (e) {
    zsFinish({ rows: [zsRow('Amazon', NAME, '-', 'ERROR', String(e), url)] });
  }
})();

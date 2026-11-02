// Runs on the Nintendo Store product page for the console.
(async function () {
  var rows = [], url = location.href.split('?')[0];
  var NAME = 'Nintendo Switch 2 – The Legend of Zelda 40th Anniversary Edition';
  try {
    if (zsBlocked()) return zsFinish({ blocked: true, rows: [] });
    var verdict = null, detail = '';
    for (var i = 0; i < 20 && !verdict; i++) {
      var body = (document.body ? document.body.innerText : '').toLowerCase();
      var addBtn = Array.prototype.find.call(document.querySelectorAll('button'), function (b) {
        return /add to cart/i.test(b.innerText || '');
      });
      // The product's own stock line sits at the top; an Add to cart lower down can be a
      // recommendation's, so sold-out wording up there wins over any live button.
      if (/out of stock|sold out|currently unavailable|no longer available/.test(body.slice(0, 2500))) {
        verdict = 'OUT OF STOCK'; detail = 'page says out of stock / sold out';
      } else if (addBtn && !addBtn.disabled && addBtn.getAttribute('aria-disabled') !== 'true') {
        verdict = 'IN STOCK'; detail = 'Add to cart is live on the Nintendo Store';
      } else if (/out of stock|sold out|currently unavailable|no longer available/.test(body)) {
        verdict = 'OUT OF STOCK'; detail = 'page says out of stock / sold out';
      } else if (addBtn) {
        verdict = 'OUT OF STOCK'; detail = 'Add to cart button is disabled';
      }
      if (!verdict) await zsSleep(750);
    }
    if (!verdict) { verdict = 'UNVERIFIED'; detail = 'no cart button or stock wording (page may only list retailers)'; }
    rows.push(zsRow('Nintendo', NAME, 'online', verdict, detail, url));
    zsFinish({ rows: rows });
  } catch (e) {
    zsFinish({ rows: [zsRow('Nintendo', NAME, '-', 'ERROR', String(e), url)] });
  }
})();

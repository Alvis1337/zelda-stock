// Shared helpers. ZS_JOB, ZS_PARAMS and the ZS bridge are defined by the app or server before this runs.
window.zsSleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
window.zsFinish = function (obj) { ZS.done(ZS_JOB, JSON.stringify(obj)); };
window.zsBlocked = function () {
  var t = (document.title + ' ' + (document.body ? document.body.innerText.slice(0, 3000) : '')).toLowerCase();
  return /robot or human|captcha|access denied|are you a human|verify you are human|px-captcha|pardon our interruption|press (&|and) hold|quick verification|confirm you.re a human/.test(t);
};
// Returns why the current page can't be trusted (bot check, error page, empty),
// or null when it looks like a real page. Never report "none found" from a bad page.
window.zsPageProblem = function () {
  if (zsBlocked()) return 'bot check';
  var body = document.body ? document.body.innerText : '';
  var head = (document.title + ' ' + body.slice(0, 1500)).toLowerCase();
  if (/something went wrong|page not found|we.re sorry|service unavailable|temporarily unavailable|internal server error|this page isn.t working|\berr_[a-z_]{4,}/.test(head))
    return 'error page ("' + (document.title || body.slice(0, 60)).trim().slice(0, 60) + '")';
  if (body.trim().length < 200) return 'page was blank';
  return null;
};
window.zsPageLabel = function () { return (document.title || location.hostname).trim().slice(0, 70); };
// schema.org Product offers from the page (or a parsed document).
window.zsJsonLd = function (doc) {
  var out = null;
  (doc || document).querySelectorAll('script[type="application/ld+json"]').forEach(function (s) {
    if (out) return;
    try {
      var d = JSON.parse(s.textContent), list = Array.isArray(d) ? d : (d['@graph'] || [d]);
      list.forEach(function (it) {
        if (out || !it || !it.offers) return;
        var offs = Array.isArray(it.offers) ? it.offers : (it.offers.offers || [it.offers]);
        offs.forEach(function (o) {
          if (!out && o && o.availability) out = {
            availability: String(o.availability).split('/').pop(), price: o.price || o.lowPrice,
            name: it.name, seller: o.seller && (o.seller.name || o.seller)
          };
        });
      });
    } catch (e) {}
  });
  return out;
};
window.zsFindButton = function (re) {
  return Array.prototype.find.call(document.querySelectorAll('button, [role="button"], input[type="submit"]'), function (b) {
    var t = (b.innerText || b.value || b.getAttribute('aria-label') || '').trim();
    return re.test(t) && b.offsetParent !== null;
  });
};
window.zsDisabled = function (b) {
  return !!(b && (b.disabled || b.getAttribute('aria-disabled') === 'true' || /disabled/.test(b.className)));
};
window.zsIsZeldaS2 = function (s) {
  s = (s || '').toLowerCase();
  return s.indexOf('zelda') >= 0 && /switch\s*2|switch™\s*2/.test(s);
};
// Only the Zelda 40th Anniversary (Ocarina of Time) Switch 2 console: no games,
// other bundles, accessories, or used/refurbished units.
window.zsIsConsole = function (s) {
  s = (s || '').toLowerCase();
  if (!zsIsZeldaS2(s)) return false;
  // Accessories in the same Zelda 40th line: cards, docks, cameras, cases and the like.
  if (/controller|joy-?con|case|screen protector|amiibo|upgrade pack|restored|refurb|pre-?owned|used|renewed|digital|download|micro ?sd|memory card|game(-key)? card|camera|tote|carrying|travel|charg|stand\b|grip|bag|pouch|skin|sleeve|cable|adapter|headset|memory|figure|plush|poster|shirt|keychain/.test(s)) return false;
  // "Dock" alone is the accessory; a console listing may say "with dock".
  if (/\bdock\b/.test(s) && !/console|system|bundle/.test(s)) return false;
  if (!/40th anniversary|ocarina of time/.test(s)) return false;
  return /console|system|bundle|hardware|anniversary edition/.test(s);
};
window.zsRow = function (retailer, product, where, status, detail, url) {
  return { retailer: retailer, product: product || '-', where: where || '-',
           status: status, detail: detail || '', url: url || '' };
};

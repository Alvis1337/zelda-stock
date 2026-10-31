// Runs on the GameStop product page for the console. GameStop sells it in stores
// only, so this does what you'd do by hand: tap "Available In Stores", enter the
// ZIP, search, and read the store list GameStop shows.
(async function () {
  var P = ZS_PARAMS, z = P.zips[0], url = location.href.split('?')[0];
  var NAME = 'Nintendo Switch 2 The Legend of Zelda 40th Anniversary Edition';
  var done = function (status, where, detail) { zsFinish({ rows: [zsRow('GameStop', NAME, where, status, detail, url)] }); };
  try {
    var problem = null, btn = null;
    for (var i = 0; i < 20 && !btn; i++) {
      problem = zsPageProblem();
      if (problem === 'bot check') return zsFinish({ blocked: true, rows: [] });
      btn = zsFindButton(/available in stores|check (other )?stores|check store availability|find (it )?in store/i);
      if (!btn) await zsSleep(750);
    }
    if (!btn) return done('UNVERIFIED', 'stores near ' + z, (problem || 'no "Available In Stores" button') + ' on ' + zsPageLabel());
    btn.click();

    // The store dialog: find its ZIP box.
    var input = null;
    for (var j = 0; j < 16 && !input; j++) {
      input = Array.prototype.find.call(document.querySelectorAll('input'), function (el) {
        var h = ((el.placeholder || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.name || '')).toLowerCase();
        return /zip|postal|city/.test(h) && el.offsetParent !== null;
      });
      if (!input) await zsSleep(500);
    }
    if (!input) return done('UNVERIFIED', 'stores near ' + z, 'store dialog did not open');
    var dialog = input.closest('[role="dialog"], [aria-modal="true"], .modal, [class*="modal"], [class*="Modal"], [class*="drawer"]') || input.form || document.body;

    // GameStop usually answers right away for the location it detects. Wait for that.
    var text = '';
    var answered = function (t) { return /no store within \d+ miles|in stock|limited stock|\d+(\.\d+)?\s*mi\b/i.test(t); };
    for (var w = 0; w < 10; w++) {
      await zsSleep(700);
      text = dialog.innerText || '';
      if (answered(text)) break;
    }
    // Then ask for our ZIP the way a person would, so the site's own code sees it.
    var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    input.focus();
    setter.call(input, z);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    await zsSleep(400);
    var before = dialog.innerText;
    var go = dialog.querySelector('button[type="submit"], [aria-label*="search" i], [title*="search" i]');
    if (!go) {
      // The search icon sits right after the box: take the next clickable element.
      var all = Array.prototype.slice.call(dialog.querySelectorAll('button, [role="button"], svg'));
      go = all.find(function (el) {
        return (input.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING) &&
               !/locat|near|current|close/i.test((el.getAttribute('aria-label') || '') + (el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className));
      });
      if (go && go.tagName.toLowerCase() === 'svg') go = go.closest('button, [role="button"], div') || go;
    }
    ['keydown', 'keypress', 'keyup'].forEach(function (t) {
      input.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    });
    if (go) go.click();
    for (var k = 0; k < 20; k++) {
      await zsSleep(750);
      var t2 = dialog.innerText || '';
      if (t2 !== before && answered(t2)) { text = t2; break; }
    }
    var none = text.match(/no store within (\d+) miles of (\d{5})[^.]*inventory/i);
    if (none) {
      return done('OUT OF STOCK', 'stores within ' + none[1] + ' mi of ' + none[2], 'GameStop: no store currently has inventory' + (none[2] !== z ? ' (GameStop used ' + none[2] + ', near you)' : ''));
    }
    var lines = text.split('\n').map(function (s) { return s.trim(); }).filter(Boolean);
    var hits = [];
    lines.forEach(function (l, idx) {
      if (/^(in stock|limited stock|available)\b/i.test(l) || /\b(in stock|limited stock)\b/i.test(l)) {
        var name = lines.slice(Math.max(0, idx - 3), idx).join(' · ');
        hits.push(name + ' → ' + l);
      }
    });
    if (hits.length) {
      return zsFinish({ rows: hits.slice(0, 8).map(function (h) {
        return zsRow('GameStop', NAME, 'store near you', 'IN STOCK', h.slice(0, 160), url);
      }) });
    }
    done('UNVERIFIED', 'stores near ' + z, 'could not read the store list: "' + text.replace(/\s+/g, ' ').slice(0, 120) + '"');
  } catch (e) {
    done('ERROR', 'stores near ' + z, String(e));
  }
})();

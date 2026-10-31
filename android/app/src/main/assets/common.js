// Shared helpers. ZS_JOB and ZS_PARAMS are defined by the app before this runs.
window.zsSleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
window.zsFinish = function (obj) { ZS.done(ZS_JOB, JSON.stringify(obj)); };
window.zsBlocked = function () {
  var t = (document.title + ' ' + (document.body ? document.body.innerText.slice(0, 3000) : '')).toLowerCase();
  return /robot or human|captcha|access denied|are you a human|verify you are human|px-captcha|pardon our interruption/.test(t);
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
  if (/controller|joy-?con|case|screen protector|amiibo|upgrade pack|restored|refurb|pre-?owned|used|renewed|digital|download|game card/.test(s)) return false;
  if (!/40th anniversary|ocarina of time/.test(s)) return false;
  return /console|system|bundle|hardware|anniversary edition/.test(s);
};
window.zsRow = function (retailer, product, where, status, detail, url) {
  return { retailer: retailer, product: product || '-', where: where || '-',
           status: status, detail: detail || '', url: url || '' };
};

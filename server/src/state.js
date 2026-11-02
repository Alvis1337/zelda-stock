// state.json on the PVC, and the rules that turn a finished run into push events.
import fs from 'node:fs';
import path from 'node:path';

// Statuses that mean the retailer's own data was read. Anything else is a blocked or broken read.
const DEFINITIVE = new Set(['IN STOCK', 'OUT OF STOCK', 'NONE FOUND']);
// A key that was in stock this recently doesn't ring again: a part of a read that fails once (one
// ZIP's store lookup, Best Buy's pickup tile) drops its key, and the next read brings it back.
export const REALERT_MS = 30 * 60_000; // more than two slow-retailer intervals (10-14 min)

export const key = (r) => `${r.retailer}|${r.product}|${r.where}`; // same as Results.key

export function emptyState() {
  return {
    lastRun: null, rows: [], inStockKeys: [], unverifiedStreak: {},
    slow: {}, heartbeatDate: null, runsSinceHeartbeat: 0, alerted: {},
  };
}

export function loadState(dataDir) {
  try {
    const s = { ...emptyState(), ...JSON.parse(fs.readFileSync(path.join(dataDir, 'state.json'), 'utf8')) };
    // Before SLOW_RETAILERS only Amazon was throttled, kept under "amazon".
    if (s.amazon) { s.slow = { Amazon: s.amazon, ...s.slow }; delete s.amazon; }
    return s;
  } catch (e) {
    if (e.code !== 'ENOENT') console.error('state.json unreadable, starting fresh:', e.message);
    return emptyState();
  }
}

export function saveState(dataDir, state) {
  const file = path.join(dataDir, 'state.json');
  fs.mkdirSync(dataDir, { recursive: true });
  fs.writeFileSync(file + '.tmp', JSON.stringify(state, null, 1));
  fs.renameSync(file + '.tmp', file);
}

/**
 * Folds a finished run into the state. `ran` lists the retailers whose pages were
 * actually visited this run (Amazon's reused rows don't count). Returns the new
 * state and the events to push.
 */
export function applyRun(prev, rows, { ran, at, blockedAfter }) {
  const state = { ...prev, unverifiedStreak: { ...prev.unverifiedStreak } };
  const events = [];

  const unreadable = new Set();
  for (const retailer of ran) {
    const mine = rows.filter((r) => r.retailer === retailer);
    if (!mine.some((r) => DEFINITIVE.has(r.status))) unreadable.add(retailer);
  }

  // In stock now and not on the previous run (nor read in stock in the last REALERT_MS).
  // One alert per retailer per run, online first: each alert opens checkout and adds
  // one to the cart, so Target online + three stores must not mean four carts.
  const before = new Set(prev.inStockKeys);
  const alerted = Object.fromEntries(Object.entries(prev.alerted || {})
    .filter(([, t]) => Date.parse(at) - Date.parse(t) < REALERT_MS));
  const now = new Set();
  const fresh = new Map();
  for (const r of rows) {
    if (r.status !== 'IN STOCK') continue;
    const k = key(r);
    if (!before.has(k) && !now.has(k) && !alerted[k]) {
      const list = fresh.get(r.retailer) || [];
      fresh.set(r.retailer, list);
      if (r.where === 'online') list.unshift({ r, k }); else list.push({ r, k });
    }
    now.add(k);
  }
  for (const list of fresh.values()) {
    events.push({ type: 'in_stock', row: list[0].r, key: list[0].k,
      keys: list.map((x) => x.k), also: list.slice(1).map((x) => x.r.where) });
    for (const x of list) alerted[x.k] = at;
  }
  state.alerted = alerted;
  // A retailer that couldn't be read this run keeps its in-stock rows, so a blip
  // between two in-stock reads doesn't ring the alarm twice. That includes a slow
  // retailer sitting this run out whose last (reused) read was unreadable.
  const blip = new Set(unreadable);
  for (const retailer of new Set(rows.map((r) => r.retailer))) {
    if (!ran.includes(retailer) && !rows.some((r) => r.retailer === retailer && DEFINITIVE.has(r.status))) blip.add(retailer);
  }
  for (const k of before) if (blip.has(k.split('|')[0])) now.add(k);

  for (const retailer of ran) {
    const streak = state.unverifiedStreak[retailer] || 0;
    if (unreadable.has(retailer)) {
      state.unverifiedStreak[retailer] = streak + 1;
      if (streak + 1 === blockedAfter) {
        const r = rows.find((x) => x.retailer === retailer) || {};
        events.push({ type: 'blocked', retailer, detail: r.detail || 'no result', url: r.url || '' });
      }
    } else {
      if (streak >= blockedAfter) events.push({ type: 'recovered', retailer });
      delete state.unverifiedStreak[retailer];
    }
  }

  state.rows = rows;
  state.lastRun = at;
  state.inStockKeys = [...now];
  // The window runs from the last in-stock read, not the alert: a key that stays in stock
  // for an hour and then misses one read must not ring again.
  for (const k of now) state.alerted[k] = at;
  // A key that's gone stays in the window unless the read shows it sold out, so a second
  // wave rings. A missing row isn't proof (the pickup tile didn't match, the cards didn't
  // render); proof is the same key reading OUT OF STOCK, or, for a store, every store
  // lookup for that product answering (Target and GameStop list only stores with stock).
  const soldOut = new Set(rows.filter((r) => r.status === 'OUT OF STOCK').map(key));
  for (const k of Object.keys(state.alerted)) {
    if (now.has(k)) continue;
    const [retailer, product, where] = k.split('|');
    const stores = rows.filter((r) => r.retailer === retailer && r.product === product && r.where !== 'online');
    const storesClean = where !== 'online' && ran.includes(retailer) && stores.length > 0 &&
      stores.every((r) => DEFINITIVE.has(r.status));
    if (soldOut.has(k) || storesClean) delete state.alerted[k];
  }
  state.runsSinceHeartbeat = (prev.runsSinceHeartbeat || 0) + 1;
  return { state, events };
}

/** Local date (YYYY-MM-DD) and hour in the given time zone. */
export function localTime(date, tz) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', hourCycle: 'h23',
  }).formatToParts(date).map((p) => [p.type, p.value]));
  return { day: `${parts.year}-${parts.month}-${parts.day}`, hour: Number(parts.hour) };
}

/** True once a day, on the first run at or after `hour` local time. */
export function heartbeatDue(state, date, tz, hour) {
  const t = localTime(date, tz);
  return t.hour >= hour && state.heartbeatDate !== t.day;
}

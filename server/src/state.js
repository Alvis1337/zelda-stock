// state.json on the PVC, and the rules that turn a finished run into push events.
import fs from 'node:fs';
import path from 'node:path';

// Statuses that mean the retailer's own data was read. Anything else is a blocked or broken read.
const DEFINITIVE = new Set(['IN STOCK', 'OUT OF STOCK', 'NONE FOUND']);

export const key = (r) => `${r.retailer}|${r.product}|${r.where}`; // same as Results.key

export function emptyState() {
  return {
    lastRun: null, rows: [], inStockKeys: [], unverifiedStreak: {},
    slow: {}, heartbeatDate: null, runsSinceHeartbeat: 0,
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

  // In stock now and not on the previous run (same as Results.saveAndNotify).
  const before = new Set(prev.inStockKeys);
  const now = new Set();
  for (const r of rows) {
    if (r.status !== 'IN STOCK') continue;
    const k = key(r);
    if (!before.has(k) && !now.has(k)) events.push({ type: 'in_stock', row: r, key: k });
    now.add(k);
  }
  // A retailer that couldn't be read this run keeps its in-stock rows, so a blip
  // between two in-stock reads doesn't ring the alarm twice.
  for (const k of before) if (unreadable.has(k.split('|')[0])) now.add(k);

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

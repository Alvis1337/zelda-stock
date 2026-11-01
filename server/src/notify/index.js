// Turns state events into push messages and sends them on every configured channel.
import { config } from '../config.js';
import * as fcm from './fcm.js';
import * as ntfy from './ntfy.js';

const CHANNELS = { fcm, ntfy };

export function message(ev, at) {
  switch (ev.type) {
    case 'in_stock':
    case 'test': {
      const r = ev.row;
      return {
        type: ev.type,
        title: `${ev.type === 'test' ? 'TEST: ' : ''}In stock at ${r.retailer}: ${r.where}`,
        body: `${r.product}\n${r.detail}`,
        url: r.url,
        data: { retailer: r.retailer, product: r.product, where: r.where, detail: r.detail,
                url: r.url, key: ev.key, checkedAt: at },
      };
    }
    case 'blocked':
      return {
        type: 'blocked',
        title: `${ev.retailer} blocked`,
        body: `${ev.retailer} hasn't read normally for ${config.blockedAfterRuns} checks. ` +
              `Open the server browser view to fix it.\n${ev.detail}`,
        url: ev.url,
        data: { retailer: ev.retailer, detail: ev.detail, url: ev.url, checkedAt: at },
      };
    case 'recovered':
      return {
        type: 'recovered',
        title: `${ev.retailer} recovered`,
        body: `${ev.retailer} is reading normally again.`,
        data: { retailer: ev.retailer, checkedAt: at },
      };
    case 'heartbeat':
      return {
        type: 'heartbeat',
        title: ev.inStock ? `Server alive: ${ev.inStock} in stock` : 'Server alive: nothing in stock',
        body: `Checked ${ev.runs} times since the last heartbeat.` +
              (ev.unreadable.length ? ` Not reading: ${ev.unreadable.join(', ')}.` : ' Every retailer read normally.'),
        data: { runs: ev.runs, inStock: ev.inStock, checkedAt: at },
      };
    default:
      throw new Error('unknown event ' + ev.type);
  }
}

/** Sends one event on every configured channel. Returns { channel: 'ok' | error }. */
export async function notify(ev, at = new Date().toISOString()) {
  const msg = message(ev, at);
  const out = {};
  for (const name of config.push) {
    const ch = CHANNELS[name];
    if (!ch) { out[name] = 'unknown channel'; continue; }
    if (!ch.enabled()) { out[name] = 'not configured'; continue; }
    try {
      await ch.send(msg);
      out[name] = 'ok';
    } catch (e) {
      out[name] = String(e.message || e);
    }
  }
  console.log(`push ${ev.type}: ${msg.title} ->`, JSON.stringify(out));
  return out;
}

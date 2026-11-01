// UnifiedPush to the Android app. The app registers its endpoint (from a distributor
// such as the ntfy app) through POST /api/register; messages are Web Push encrypted
// (RFC 8291) with the keys the app sent, so the distributor can't read them.
import fs from 'node:fs';
import path from 'node:path';
import webpush from 'web-push';
import { config } from '../config.js';

const file = () => path.join(config.dataDir, 'subscriptions.json');

export function list() {
  try {
    return JSON.parse(fs.readFileSync(file(), 'utf8'));
  } catch {
    return [];
  }
}

function save(subs) {
  fs.mkdirSync(config.dataDir, { recursive: true });
  fs.writeFileSync(file() + '.tmp', JSON.stringify(subs, null, 1));
  fs.renameSync(file() + '.tmp', file());
}

/** Adds or refreshes one app registration. Throws on a malformed one. */
export function add({ endpoint, p256dh, auth, device }) {
  const u = new URL(endpoint);
  if (u.protocol !== 'https:') throw new Error('endpoint must be https');
  if (!p256dh || !auth) throw new Error('p256dh and auth are required');
  const subs = list().filter((s) => s.endpoint !== endpoint);
  subs.push({ endpoint, p256dh, auth, device: String(device || '').slice(0, 80), at: new Date().toISOString() });
  save(subs);
  return subs.length;
}

export function remove(endpoint) {
  save(list().filter((s) => s.endpoint !== endpoint));
}

export const enabled = () => list().length > 0;

export async function send(msg) {
  const subs = list();
  const payload = JSON.stringify({ type: msg.type, title: msg.title, body: msg.body, url: msg.url || '', ...msg.data });
  const urgent = msg.type === 'in_stock' || msg.type === 'test';
  const errors = [];
  for (const s of subs) {
    try {
      await webpush.sendNotification({ endpoint: s.endpoint, keys: { p256dh: s.p256dh, auth: s.auth } }, payload, {
        TTL: 300, urgency: urgent ? 'high' : 'normal', contentEncoding: 'aes128gcm', timeout: 15_000,
      });
    } catch (e) {
      // The distributor dropped this registration (app uninstalled or re-registered).
      if (e.statusCode === 404 || e.statusCode === 410) remove(s.endpoint);
      errors.push(`${new URL(s.endpoint).host}: ${e.statusCode || ''} ${e.body || e.message}`.trim());
    }
  }
  if (errors.length && errors.length === subs.length) throw new Error(errors.join('; '));
}

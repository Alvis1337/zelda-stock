// Verifies the Cf-Access-Jwt-Assertion header Cloudflare Access adds to requests it
// let through (service token or login), so the API trusts only traffic that passed Access.
import crypto from 'node:crypto';
import { config } from './config.js';

let certs = { at: 0, keys: new Map() };

async function keys(force) {
  // Refetch hourly, or on an unknown key id (rotation), but at most once a minute.
  const age = Date.now() - certs.at;
  if (certs.keys.size && age < 3_600_000 && !(force && age > 60_000)) return certs.keys;
  const res = await fetch(`https://${config.cfAccessTeam}/cdn-cgi/access/certs`, { signal: AbortSignal.timeout(10_000) });
  if (!res.ok) throw new Error(`Access certs HTTP ${res.status}`);
  const { keys: jwks } = await res.json();
  certs = { at: Date.now(), keys: new Map(jwks.map((k) => [k.kid, crypto.createPublicKey({ key: k, format: 'jwk' })])) };
  return certs.keys;
}

const part = (s) => JSON.parse(Buffer.from(s, 'base64url').toString('utf8'));

export const accessEnabled = () => !!(config.cfAccessTeam && config.cfAccessAud);

export async function verifyAccess(token) {
  if (!accessEnabled() || !token) return false;
  try {
    const [h, p, sig] = token.split('.');
    if (!sig) return false;
    const header = part(h);
    const payload = part(p);
    if (header.alg !== 'RS256') return false;
    let key = (await keys()).get(header.kid);
    if (!key) key = (await keys(true)).get(header.kid);
    if (!key) return false;
    if (!crypto.verify('RSA-SHA256', Buffer.from(`${h}.${p}`), key, Buffer.from(sig, 'base64url'))) return false;
    const aud = [].concat(payload.aud);
    return aud.includes(config.cfAccessAud) && payload.iss === `https://${config.cfAccessTeam}` &&
      payload.exp * 1000 > Date.now();
  } catch (e) {
    console.log('Access JWT check failed:', e.message);
    return false;
  }
}

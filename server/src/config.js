// All settings come from the environment (ConfigMap / Secret in k8s).
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const env = process.env;
const here = path.dirname(fileURLToPath(import.meta.url));
const num = (v, d) => (v === undefined || v === '' || isNaN(Number(v)) ? d : Number(v));
const list = (v, d) => (v || d).split(/[\s,]+/).filter(Boolean);

export const config = {
  zips: list(env.ZIPS, '45780 43130 45701'),
  radius: num(env.RADIUS_MI, 50),
  intervalMin: Math.max(5, num(env.INTERVAL_MIN, 5)), // a full run takes 2-4 min
  // Retailers that throttle frequent visits run at most every N minutes; in between,
  // their last rows are shown again. "Retailer:minutes,...", "_" for a space in a name.
  slowRetailers: Object.fromEntries(list(env.SLOW_RETAILERS, 'Amazon:10,Target:10,Best_Buy:10')
    .map((s) => s.split(':')).filter(([, m]) => m && !isNaN(Number(m))).map(([r, m]) => [r.replace(/_/g, ' '), Number(m)])),
  heartbeatHour: num(env.HEARTBEAT_HOUR, 9),
  blockedAfterRuns: num(env.BLOCKED_AFTER_RUNS, 3),
  tz: env.TZ || 'America/New_York',
  dataDir: env.DATA_DIR || path.resolve(here, '../data'),
  checksDir: env.CHECKS_DIR || path.resolve(here, '../../checks'),
  port: num(env.PORT, 8080),
  apiToken: env.API_TOKEN || '',
  // Cloudflare Access in front of the public hostname: team domain and the app's AUD tag.
  cfAccessTeam: env.CF_ACCESS_TEAM || '',
  cfAccessAud: env.CF_ACCESS_AUD || '',
  push: list(env.PUSH, 'ntfy'),
  fcmTopic: env.FCM_TOPIC || 'zelda-oot',
  ntfyUrl: (env.NTFY_URL || 'https://ntfy.sh').replace(/\/+$/, ''),
  ntfyTopic: env.NTFY_TOPIC || '',
  ntfyToken: env.NTFY_TOKEN || '',
  headless: env.HEADLESS === '1', // only for local testing; retailers flag headless Chromium
};

// Approximate coordinates, granted to the page as its geolocation (GameStop's store
// lookup uses it). Unknown ZIPs keep whatever location was set last.
export const ZIP_COORDS = {
  '45780': { latitude: 39.369, longitude: -82.137 },
  '43130': { latitude: 39.714, longitude: -82.599 },
  '45701': { latitude: 39.329, longitude: -82.101 },
};

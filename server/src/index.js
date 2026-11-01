// Zelda stock server: runs the checks on a jittered schedule, keeps state.json,
// pushes alerts on changes, and serves a small HTTP API for the app.
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import nodePath from 'node:path';
import { accessEnabled, verifyAccess } from './access.js';
import { Browser, row } from './browser.js';
import { config } from './config.js';
import { AMAZON, buildJobs } from './jobs.js';
import { notify } from './notify/index.js';
import * as up from './notify/up.js';
import { applyRun, heartbeatDue, loadState, localTime, saveState } from './state.js';

const STUCK_MS = 20 * 60_000; // a run normally takes 2-4 min; past this, let k8s restart the pod

const browser = new Browser();
let state = loadState(config.dataDir);
let running = null;
let paused = false;
let nextRunAt = null;
let timer = null;
let jobSeq = 0;
let shuttingDown = false;

const log = (...a) => console.log(new Date().toISOString(), ...a);
const jittered = () => config.intervalMin * 60_000 * (0.8 + Math.random() * 0.4);
const clock = (iso) => new Date(iso).toLocaleTimeString('en-US', { timeZone: config.tz, hour: 'numeric', minute: '2-digit' });

async function runOnce(reason) {
  if (running) return;
  running = { startedAt: new Date().toISOString(), reason, progress: null };
  log(`run start (${reason})`);
  try {
    const at = new Date();
    const amazonDue = !state.amazon || at - Date.parse(state.amazon.at) >= config.amazonEveryMin * 60_000;
    const rows = [];
    const ran = new Set();
    let amazonRows = null;
    for (const job of buildJobs(config.zips, config.radius, amazonDue)) {
      if (paused) { log('run stopped: paused'); return; }
      running.progress = job.retailer;
      const t0 = Date.now();
      const rs = await browser.runJob(job, ++jobSeq);
      log(`  ${job.retailer} (${job.script}${job.params.zips.length === 1 ? ' ' + job.zip : ''}) ${((Date.now() - t0) / 1000).toFixed(1)}s:`,
        rs.map((r) => `${r.status} [${r.where}] ${r.detail}`).join(' | '));
      rows.push(...rs);
      ran.add(job.retailer);
      if (job.retailer === AMAZON) amazonRows = rs;
    }
    // Amazon throws bot checks at frequent visits; in between, show its last result again.
    if (amazonRows) state.amazon = { at: at.toISOString(), rows: amazonRows };
    else if (state.amazon) {
      for (const r of state.amazon.rows) rows.push({ ...r, detail: `${r.detail} (checked ${clock(state.amazon.at)}; Amazon runs every ${config.amazonEveryMin} min)` });
    }

    const finished = new Date();
    const { state: next, events } = applyRun(state, rows,
      { ran: [...ran], at: finished.toISOString(), blockedAfter: config.blockedAfterRuns });
    state = next;
    if (heartbeatDue(state, finished, config.tz, config.heartbeatHour)) {
      events.push({ type: 'heartbeat', runs: state.runsSinceHeartbeat, inStock: state.inStockKeys.length,
        unreadable: Object.keys(state.unverifiedStreak) });
      state.heartbeatDate = localTime(finished, config.tz).day;
      state.runsSinceHeartbeat = 0;
    }
    saveState(config.dataDir, state);
    const count = (s) => rows.filter((r) => r.status === s).length;
    log(`run done in ${((finished - at) / 1000).toFixed(0)}s: ${count('IN STOCK')} in stock, ${count('OUT OF STOCK')} out, ` +
      `${count('UNVERIFIED') + count('ERROR')} unverified/error; ${events.length} push event(s)`);
    for (const ev of events) await notify(ev, finished.toISOString());
  } catch (e) {
    log('run failed:', e);
  } finally {
    running = null;
    await browser.idle();
  }
}

function schedule(ms) {
  clearTimeout(timer);
  nextRunAt = Date.now() + ms;
  timer = setTimeout(async () => {
    nextRunAt = null;
    if (!paused) await runOnce('schedule');
    if (!shuttingDown) schedule(jittered());
  }, ms);
}

// Either the API token (kubectl port-forward) or a request Cloudflare Access let through.
async function authorized(req) {
  if (config.apiToken) {
    const want = Buffer.from('Bearer ' + config.apiToken);
    const got = Buffer.from(req.headers.authorization || '');
    if (got.length === want.length && crypto.timingSafeEqual(got, want)) return true;
  }
  return verifyAccess(req.headers['cf-access-jwt-assertion']);
}

async function readJson(req) {
  let body = '';
  for await (const chunk of req) {
    body += chunk;
    if (body.length > 16_384) throw new Error('body too large');
  }
  return body ? JSON.parse(body) : {};
}

const SHOTS = () => nodePath.join(config.dataDir, 'shots');

const server = http.createServer(async (req, res) => {
  const send = (code, obj) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(obj));
  };
  const path = new URL(req.url, 'http://localhost').pathname;
  const route = `${req.method} ${path}`;
  try {
    if (route === 'GET /healthz') {
      const stuck = running && Date.now() - Date.parse(running.startedAt) > STUCK_MS;
      return send(stuck ? 500 : 200, { ok: !stuck });
    }
    if (route === 'GET /readyz') return send(browser.ready ? 200 : 503, { ready: browser.ready });
    if (!path.startsWith('/api/')) return send(404, { error: 'not found' });
    if (!(await authorized(req))) return send(401, { error: 'unauthorized' });

    // Latest screenshot / page text per job: /api/shots lists them, /api/shots/<name> serves one.
    const shot = path.match(/^\/api\/shots\/([a-z0-9_-]+\.(png|txt))$/);
    if (req.method === 'GET' && shot) {
      const f = nodePath.join(SHOTS(), shot[1]);
      if (!fs.existsSync(f)) return send(404, { error: 'no such screenshot' });
      res.writeHead(200, { 'Content-Type': shot[2] === 'png' ? 'image/png' : 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
      return fs.createReadStream(f).pipe(res);
    }

    switch (route) {
      case 'GET /api/shots': {
        const files = fs.existsSync(SHOTS()) ? fs.readdirSync(SHOTS()).filter((f) => f.endsWith('.png')) : [];
        return send(200, files.map((f) => ({ name: f, at: fs.statSync(nodePath.join(SHOTS(), f)).mtime.toISOString(),
          path: `/api/shots/${f}`, text: `/api/shots/${f.replace(/\.png$/, '.txt')}` })));
      }
      case 'POST /api/register': {
        const b = await readJson(req);
        const count = up.add(b);
        log(`registered push endpoint ${new URL(b.endpoint).host} (${b.device || 'unknown device'}), ${count} total`);
        return send(200, { registered: true });
      }
      case 'POST /api/unregister': {
        const b = await readJson(req);
        if (b.endpoint) up.remove(b.endpoint);
        return send(200, { registered: false });
      }
      case 'GET /api/status':
        return send(200, {
          lastRun: state.lastRun,
          nextRun: nextRunAt ? new Date(nextRunAt).toISOString() : null,
          paused,
          running: running && { startedAt: running.startedAt, progress: running.progress },
          zips: config.zips,
          radius: config.radius,
          amazonCheckedAt: state.amazon && state.amazon.at,
          pushDevices: up.list().length,
          rows: state.rows,
        });
      case 'POST /api/check':
        if (paused) return send(409, { error: 'paused; POST /api/resume first' });
        if (running) return send(202, { started: false, running: true, progress: running.progress });
        clearTimeout(timer);
        nextRunAt = null;
        runOnce('manual').then(() => { if (!shuttingDown) schedule(jittered()); });
        return send(202, { started: true });
      case 'POST /api/test-alert': {
        const r = row('Test', 'Zelda 40th Anniversary console (test alert)', 'this is only a test', 'IN STOCK',
          'Sent by POST /api/test-alert. Real alerts open checkout and add one to your cart.',
          'https://www.bestbuy.com/product/switch-2-the-legend-of-zelda-40th-anniversary-edition/J7GSL57HTY');
        return send(200, { sent: await notify({ type: 'test', row: r, key: 'test' }) });
      }
      case 'POST /api/pause':
        paused = true;
        log('paused');
        // A run in progress stops before its next page, so it won't navigate away mid-solve.
        return send(200, { paused, running: !!running });
      case 'POST /api/resume':
        paused = false;
        log('resumed');
        if (!running && !nextRunAt) schedule(jittered());
        return send(200, { paused });
      default:
        return send(404, { error: 'not found' });
    }
  } catch (e) {
    log('http error:', e);
    if (!res.headersSent) send(500, { error: String(e.message || e) });
  }
});

async function shutdown(sig) {
  if (shuttingDown) return;
  shuttingDown = true;
  log(`${sig}: shutting down`);
  clearTimeout(timer);
  server.close();
  await browser.close();
  process.exit(0);
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

log(`zelda-stock-server: ZIPs ${config.zips.join(' ')}, ${config.radius} mi, every ~${config.intervalMin} min, ` +
  `push ${config.push.join(',')}, data ${config.dataDir}`);
if (!config.apiToken && !accessEnabled()) log('warning: neither API_TOKEN nor CF_ACCESS_* set, /api/* will refuse every request');
server.listen(config.port, () => log(`listening on :${config.port}`));
try {
  await browser.launch(() => {
    if (shuttingDown) return;
    log('browser closed unexpectedly, exiting so the pod restarts');
    process.exit(1);
  });
  log('browser ready');
} catch (e) {
  log('browser launch failed:', e);
  process.exit(1);
}
schedule(10_000);

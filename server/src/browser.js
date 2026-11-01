// Drives one persistent Chromium profile and runs the checks/ scripts in it,
// the way StockChecker does with the app's WebView.
import fs from 'node:fs';
import path from 'node:path';
import { chromium, devices } from 'playwright';
import { config, ZIP_COORDS } from './config.js';

const LOAD_TIMEOUT_MS = 45_000;
const JOB_TIMEOUT_MS = 60_000;
const SETTLE_MS = 2_500; // same as StockChecker

export function row(retailer, product, where, status, detail, url) {
  return { retailer, product: product || '-', where: where || '-', status, detail: detail || '', url: url || '' };
}

export const shotName = (job) => job.script.replace(/\.js$/, '') + (job.script === 'gamestop_store.js' ? '-' + job.zip : '');

const short = (e) => String((e && e.message) || e).split('\n')[0].slice(0, 160);

export class Browser {
  constructor() {
    this.context = null;
    this.page = null;
  }

  get ready() { return !!this.context; }

  script(name) {
    // Read on every run so a changed checks/ folder (local dev) is picked up.
    return fs.readFileSync(path.join(config.checksDir, name), 'utf8');
  }

  async launch(onClose) {
    const profile = path.join(config.dataDir, 'profile');
    fs.mkdirSync(profile, { recursive: true });
    // A new pod has a new hostname; Chromium refuses a profile locked by "another computer".
    for (const f of ['SingletonLock', 'SingletonSocket', 'SingletonCookie']) {
      fs.rmSync(path.join(profile, f), { force: true });
    }
    // The scripts were written against the mobile sites, so look like a phone.
    const { defaultBrowserType: _ignored, ...phone } = devices['Pixel 7'];
    const z = ZIP_COORDS[config.zips[0]];
    this.context = await chromium.launchPersistentContext(profile, {
      ...phone,
      headless: config.headless,
      locale: 'en-US',
      timezoneId: config.tz,
      permissions: ['geolocation'],
      geolocation: z,
      // The scripts are injected as inline <script> tags; don't let a site's CSP block them.
      bypassCSP: true,
      ignoreDefaultArgs: ['--enable-automation'],
      args: ['--disable-blink-features=AutomationControlled', '--disable-dev-shm-usage'],
    });
    this.context.on('close', () => { this.context = null; onClose && onClose(); });
    this.page = this.context.pages()[0] || (await this.context.newPage());
    await this.page.goto('about:blank');
  }

  async close() {
    if (this.context) await this.context.close().catch(() => {});
  }

  async pageFor() {
    if (!this.page || this.page.isClosed()) this.page = await this.context.newPage();
    return this.page;
  }

  /** Runs one job and returns its rows. Never throws. Leaves a screenshot of the page. */
  async runJob(job, jobId) {
    const rows = await this.runJobInner(job, jobId);
    await this.shot(job, rows);
    return rows;
  }

  /** Latest screenshot + page text per job, in DATA_DIR/shots, for checking what a site showed. */
  async shot(job, rows) {
    if (!this.page || this.page.isClosed()) return;
    try {
      const dir = path.join(config.dataDir, 'shots');
      fs.mkdirSync(dir, { recursive: true });
      const name = shotName(job);
      await this.page.screenshot({ path: path.join(dir, name + '.png'), timeout: 10_000 });
      const text = await this.page.evaluate(() => (document.body ? document.body.innerText : '')).catch(() => '');
      fs.writeFileSync(path.join(dir, name + '.txt'),
        `${new Date().toISOString()} ${this.page.url()}\n${JSON.stringify(rows, null, 1)}\n\n${text.slice(0, 20_000)}`);
    } catch (e) {
      console.log(`screenshot ${job.script} failed: ${short(e)}`);
    }
  }

  async runJobInner(job, jobId) {
    const blank = (status, detail) => [row(job.retailer, '-', '-', status, detail, job.url)];
    let page;
    try {
      page = await this.pageFor();
      if (ZIP_COORDS[job.zip]) await this.context.setGeolocation(ZIP_COORDS[job.zip]);
    } catch (e) {
      return blank('ERROR', 'browser: ' + short(e));
    }

    // A failed load leaves its error page still navigating, which would cut off the
    // next load too. Reset to about:blank after a failure, and retry network errors once.
    for (let attempt = 1; ; attempt++) {
      try {
        await page.goto(job.url, { waitUntil: 'domcontentloaded', timeout: LOAD_TIMEOUT_MS });
        break;
      } catch (e) {
        await page.goto('about:blank').catch(() => {});
        if (attempt === 1 && /net::ERR_|interrupted by another navigation/.test(short(e))) {
          await page.waitForTimeout(3_000);
          continue;
        }
        return blank('UNVERIFIED', 'page did not load: ' + short(e));
      }
    }
    // Like onPageFinished, but don't give up on pages whose trackers keep "load" from firing.
    await page.waitForLoadState('load', { timeout: 20_000 }).catch(() => {});
    await page.waitForTimeout(SETTLE_MS);

    const prefix =
      `var ZS_JOB = ${JSON.stringify(String(jobId))};\n` +
      `var ZS_PARAMS = ${JSON.stringify(job.params)};\n` +
      'window.__zsResult = new Promise(function (r) { window.ZS = { done: function (id, j) { r([id, j]); } }; });\n';
    let json;
    try {
      // addScriptTag runs in the page's global scope, like evaluateJavascript in the app.
      await page.addScriptTag({ content: prefix + this.script('common.js') + '\n' + this.script(job.script) });
      let timer;
      const result = page.evaluate(() => window.__zsResult);
      result.catch(() => {}); // still pending when the next job navigates away
      const out = await Promise.race([
        result,
        new Promise((r) => { timer = setTimeout(() => r(null), JOB_TIMEOUT_MS); }),
      ]).finally(() => clearTimeout(timer));
      if (!out) return blank('UNVERIFIED', `timed out after ${JOB_TIMEOUT_MS / 1000}s (script never finished)`);
      if (out[0] !== String(jobId)) return blank('ERROR', 'result came from another job');
      json = out[1];
    } catch (e) {
      const navigated = /context was destroyed|navigat/i.test(short(e));
      return blank('UNVERIFIED', navigated ? 'page navigated away before the check finished' : 'script failed: ' + short(e));
    }

    const rows = [];
    try {
      const res = JSON.parse(json);
      if (res.blocked) {
        rows.push(row(job.retailer, '-', '-', 'UNVERIFIED', job.retailer === 'Amazon'
          ? 'bot check. Sign in to Amazon in the server browser view (signed-in visits rarely get one), or solve it there.'
          : 'bot check shown. Solve it in the server browser view (noVNC), then Check now.', job.url));
      }
      for (const r of res.rows || []) rows.push(r);
    } catch (e) {
      rows.push(row(job.retailer, '-', '-', 'ERROR', 'could not read result: ' + short(e), job.url));
    }
    return rows;
  }

  /** Leave the last retailer page so its scripts don't run between checks. */
  async idle() {
    if (this.page && !this.page.isClosed()) await this.page.goto('about:blank').catch(() => {});
  }
}

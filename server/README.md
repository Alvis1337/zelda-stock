# Zelda stock server (k8s) + thin Android client

**Status: built and running.** Server on the cluster, API at
`https://zelda-stock.chrisalvis.me` behind Cloudflare Access, app is a push-only
client (UnifiedPush instead of the FCM design below: no Firebase project needed).
The design below is kept as the reference; "Running it" describes what is built.

## Running it

What's in the repo:

- `checks/`: the retailer scripts, shared by the app (`assets.srcDirs`) and the server.
- `server/`: Node + Playwright. `src/index.js` (scheduler + API), `browser.js`
  (persistent phone-emulated Chromium, script bridge), `jobs.js`, `state.js`
  (alert rules), `notify/` (ntfy, FCM).
- `deploy/k8s/`: kustomize manifests for namespace `zelda-stock`.
- `.github/workflows/server.yml`: builds `linux/amd64` (every node is
  amd64) and pushes `ghcr.io/alvis1337/zelda-stock-server:<sha>`; pushes to
  `main` also move `:latest`.

Differences from the design:

- noVNC runs **inside the server container** (Xvfb `:99`, x11vnc and websockify
  bound to `127.0.0.1`), not as a sidecar. Only `kubectl port-forward` can reach
  it; the Service exposes 8080 only. Set `VNC=0` to turn it off.
- `pwuser` is **uid/gid 1001** in the Playwright noble image, so the pod runs as
  1001 with `fsGroup: 1001`.
- Push is **UnifiedPush** (`PUSH=up,ntfy`), not FCM. The app registers with a
  distributor (the ntfy app), sends its endpoint and Web Push keys to
  `POST /api/register`, and the server sends RFC 8291 encrypted messages
  (`web-push`). Endpoints live in `/data/subscriptions.json`; a 404/410 from the
  distributor drops one. The ntfy topic is only a fallback (`PUSH_FALLBACK=ntfy`):
  it is used when no phone is registered or the push to every phone failed.
- The API is public only through Cloudflare Access app **Zelda Stock**
  (`zelda-stock.chrisalvis.me`): the service token `zelda-stock-app` plus the
  existing admin WARP/Google policies; anything else gets a 401/login redirect at
  Cloudflare. The server also verifies the `Cf-Access-Jwt-Assertion` header
  (`CF_ACCESS_TEAM`, `CF_ACCESS_AUD`). `API_TOKEN` still works for port-forward.
  The token's client ID / secret are in Secret `zelda-stock/zelda-stock-access`;
  the app takes them in **Server settings**, nothing is built into the APK.
- Every job leaves a screenshot and the page text in `/data/shots`
  (`GET /api/shots`, `GET /api/shots/<job>.png|.txt`).
- A retailer counts as blocked for a run when **none** of its rows is
  `IN STOCK`, `OUT OF STOCK` or `NONE FOUND`. While a retailer is unreadable its
  in-stock keys are carried over, so in stock → blocked → in stock rings once.
- A new `IN STOCK` read is never alerted on its own: the server waits 5 s and
  runs that job again, and only the second read's rows count. A false read costs
  one extra page visit; a real restock alerts about 30 s later.
- `/healthz` fails if a run has been going for over 20 minutes, so k8s restarts
  a wedged browser. The pod also exits if Chromium dies.

Local run (needs `npx playwright install --with-deps chromium` and `xvfb`):

```
cd server && npm ci
DATA_DIR=./data API_TOKEN=dev xvfb-run -a -s "-screen 0 1080x2200x24" node src/index.js
curl -H "Authorization: Bearer dev" localhost:8080/api/status
```

First deploy:

```
# 1. After the image workflow has run on main. If the GHCR package is private,
#    make it public (package settings) or add an imagePullSecret named ghcr.
kubectl create namespace zelda-stock
kubectl -n zelda-stock create secret generic zelda-stock \
  --from-literal=API_TOKEN=$(openssl rand -hex 24) \
  --from-literal=NTFY_TOPIC=zelda-$(openssl rand -hex 8)
kubectl -n zelda-stock get secret zelda-stock -o jsonpath='{.data.NTFY_TOPIC}' | base64 -d
#    ^ subscribe to this topic in the ntfy app (server ntfy.sh)
kubectl apply -k deploy/k8s
kubectl -n zelda-stock logs -f deploy/zelda-stock-server

# 2. Test the alert end to end with the phone locked.
kubectl -n zelda-stock port-forward svc/zelda-stock-server 8080:8080 &
TOKEN=$(kubectl -n zelda-stock get secret zelda-stock -o jsonpath='{.data.API_TOKEN}' | base64 -d)
curl -X POST -H "Authorization: Bearer $TOKEN" localhost:8080/api/test-alert
```

API from a laptop: `deploy/zs-api.sh GET /api/status` (port-forward + token).
`POST /api/test-alert` takes `{"kind": "test" | "in_stock" | "blocked" | "recovered" | "heartbeat", "retailer", "url", "product"}`;
`in_stock` is a simulated restock with checkout assist on. Phone tests: `docs/phone-test-plan.md`.

New image: `kubectl -n zelda-stock rollout restart deploy/zelda-stock-server`
(the Deployment runs `:latest` with `imagePullPolicy: Always`). ConfigMap and
Secret changes restart the pod on their own (Stakater Reloader annotation).

Solving a bot check / signing in to Amazon:

```
curl -X POST -H "Authorization: Bearer $TOKEN" localhost:8080/api/pause
kubectl -n zelda-stock port-forward deploy/zelda-stock-server 6080:6080
# open http://localhost:6080/vnc.html, solve it / sign in, then:
curl -X POST -H "Authorization: Bearer $TOKEN" localhost:8080/api/resume
curl -X POST -H "Authorization: Bearer $TOKEN" localhost:8080/api/check
```

Phone setup (once):

1. Install the ntfy app (it is the UnifiedPush distributor; it can also
   subscribe to the backup topic: `kubectl -n zelda-stock get secret zelda-stock
   -o jsonpath='{.data.NTFY_TOPIC}' | base64 -d`).
2. Update Zelda Stock (in-app updater), open **Server settings**, paste the
   client ID and secret from
   `kubectl -n zelda-stock get secret zelda-stock-access -o json | jq '.data|map_values(@base64d)'`,
   tap **Save and connect**.
3. Tap **Set up alerts**, then **Test from server** with the phone locked: the
   alarm should ring and open the page.

Cloudflare tunnel: the tunnel's ingress list is explicit per hostname, so
`zelda-stock.chrisalvis.me` needs a rule before the `http_status:404` catch-all,
the same as the other tunnel-gateway hosts:
service `https://envoy-kakariko-tunnel.envoy-gateway-system.svc.cluster.local:443`,
origin server name `zelda-stock.chrisalvis.me`.

State of each retailer (2026-11-01, from the screenshots):

- Walmart, GameStop (online + store lookup), Nintendo, Amazon: real reads.
- Target: real reads online and for stores near all three ZIPs. Two script
  fixes were needed: the product card lookup returned the image wrapper (empty
  text, so the online row was dropped) and redsky answers 435 to the shopper's
  cookie session, so `target.js` now calls it without cookies.
- Best Buy: the profile's Akamai bot-manager cookies got flagged after several
  pod restarts in a few minutes; with them every load is
  `ERR_HTTP2_PROTOCOL_ERROR`, without them the page loads. The server does not
  clear them on its own. To reset by hand, run `deploy/clear-site-cookies.sh`
  (pauses, clears bestbuy.com cookies via `POST /api/clear-cookies`, resumes,
  checks, prints Best Buy's rows), or do it in the noVNC view under
  `chrome://settings/content/all`.
- GameStop answers for the location it detects from the IP (43068), so the three
  store lookups return the same "no store within 100 miles" answer.

## Why

The Android app does all the work on the phone today: `WatchService` wakes the
phone every few minutes with an exact alarm and a wake lock, renders up to seven
full retailer pages in an offscreen WebView (`StockChecker`), and keeps a
foreground service alive. That is what drains the battery.

The fix is to move the checking to a server on the home k8s cluster and make the
app a client that only wakes when the server pushes an alert. A home cluster
also helps with blocking: Target, Walmart and GameStop block GitHub Actions
runners (datacenter IPs), but a residential IP looks like the phone did.

## What is being watched (do not widen this)

- Only the **Nintendo Switch 2 – The Legend of Zelda 40th Anniversary
  (Ocarina of Time) Edition console**. No games, other bundles, accessories,
  used/refurbished units. The filter is `zsIsConsole()` in `common.js`.
- Only offers **sold by the retailer itself**. Marketplace / third-party
  sellers are scalpers and never count as in stock.
- Stores near **The Plains OH (45780), Lancaster OH (43130), Athens OH (45701)**,
  50 mile radius by default.
- Only report `IN STOCK` when the retailer's own data says so. Bot checks, error
  pages and blank pages are `UNVERIFIED`, never "none found".

## Architecture

```
            home k8s cluster                                   phone
 ┌──────────────────────────────────────────────┐
 │ Deployment: zelda-stock-server (1 replica)   │
 │  ┌──────────────┐   ┌──────────────────────┐ │   FCM (high-priority   ┌──────────────┐
 │  │ scheduler +  │──▶│ Chromium (Playwright)│ │   data message)        │ Zelda Stock  │
 │  │ HTTP API     │   │ runs checks/*.js     │ │ ─────────────────────▶ │ app          │
 │  │ (Node)       │   └──────────────────────┘ │                        │ - alarm alert│
 │  │  state.json  │──▶ notifier: FCM / ntfy    │                        │ - checkout   │
 │  └──────────────┘                            │ ◀───── GET /api/status │ - status view│
 │  PVC: browser profile + state                │   (only when app open) └──────────────┘
 │  optional sidecar: noVNC (solve bot checks)  │
 └──────────────────────────────────────────────┘
```

- **Server**: one pod, Node + Playwright driving a real Chromium. It runs the
  **exact same check scripts** the app runs today, every N minutes, for all three
  ZIPs, keeps the last results and which rows were in stock, and pushes only on
  changes.
- **Push**: Firebase Cloud Messaging topic message (recommended, see below).
  Outbound only, so the cluster needs no inbound port for alerts to work.
- **App**: no background work. It receives the push, rings the existing loud
  alarm, opens the existing assisted checkout on tap (checkout must stay on the
  phone because that is where you are signed in), and shows the latest results
  from the server when you open it.

## Server component

### Reuse the check scripts, don't rewrite them

The checks are browser JavaScript in `android/app/src/main/assets/*.js`. They
already encode a lot of hard-won retailer knowledge (Walmart's own-offer check,
Best Buy's pickup tile, GameStop's "Available In Stores" ZIP flow, Amazon's
sold-by check, error-page detection). The server must run these files
unchanged rather than porting them to Python. `zelda_stock.py` is the older
API-based checker and is **not** the base for the server.

1. Move the scripts to a top-level `checks/` folder (single source of truth):
   `common.js, target.js, walmart.js, bestbuy.js, gamestop.js,
   gamestop_store.js, nintendo.js, amazon.js` (and `assist.js`, which only the
   app uses for checkout).
2. In `android/app/build.gradle` point assets at it:
   `android { sourceSets { main { assets.srcDirs += ['../../checks'] } } }`
   and add `checks/**` to the `paths:` filter in `.github/workflows/android.yml`.
3. The server image copies `checks/` in (Docker build context = repo root).

### The bridge the scripts expect

Each script reads two globals and reports back through one call
(see `StockChecker.java` and `common.js`):

- `ZS_PARAMS = { zips: [...], radius: 50 }`
- `ZS_JOB = '<job id>'`
- `ZS.done(jobId, jsonString)` with `{ blocked?: bool, rows: [row...] }`
- a row is `{ retailer, product, where, status, detail, url }`, status one of
  `IN STOCK | OUT OF STOCK | UNVERIFIED | ERROR`

Playwright equivalent, per job:

```js
await context.exposeBinding('__zsDone', (_src, id, json) => resolveJob(id, json));
await context.addInitScript(() => { window.ZS = { done: (id, j) => window.__zsDone(id, j) }; });

await page.goto(job.url, { waitUntil: 'load', timeout: 60_000 });
await page.waitForTimeout(2_500);                       // SETTLE_MS in StockChecker
await page.addScriptTag({ content:                       // runs in page global scope
  `var ZS_JOB='${id}'; var ZS_PARAMS=${JSON.stringify(params)};\n` + common + job.script });
// wait for resolveJob(id) or 60 s, then UNVERIFIED "timed out after 60s"
```

Use `addScriptTag`, not `page.evaluate(string)`, because the scripts declare
`var`s and `window.zs*` helpers that must live in the page's global scope. If a
site's CSP blocks inline scripts, fall back to `page.evaluate` with the script
wrapped as `new Function(...)`; check Target and Best Buy first.

### Job list (mirror `StockChecker.run`)

| Retailer | URL | Script | Runs |
|---|---|---|---|
| Best Buy | `https://www.bestbuy.com/site/6691841.p?skuId=6691841` | bestbuy.js | once per run |
| Target | `https://www.target.com/s?searchTerm=nintendo+switch+2+zelda+40th+anniversary+edition` | target.js | once, `zips` = all three (script loops over them) |
| Walmart | `https://www.walmart.com/ip/21002656445` | walmart.js | once per run |
| GameStop (online) | `https://www.gamestop.com/search/?q=switch+2+zelda+40th+anniversary&lang=default` | gamestop.js | once per run |
| GameStop (stores) | `https://www.gamestop.com/consoles-hardware/nintendo-switch-2/products/nintendo-switch-2-the-legend-of-zelda-40th-anniversary-edition/20037854.html` | gamestop_store.js | **once per ZIP** (script only reads `zips[0]`) |
| Nintendo | `https://www.nintendo.com/us/store/products/nintendo-switch-2-the-legend-of-zelda-40th-anniversary-edition-121642/` | nintendo.js | once per run |
| Amazon | `https://www.amazon.com/dp/B0HJ6F8L6V` | amazon.js | at most every 10 min; reuse last rows in between (same as the app) |

Run jobs one at a time in a single browser context (like the app). A full run
is roughly 2–4 minutes, so don't schedule faster than every 5 minutes. Add
±20% jitter so visits don't land on an exact cadence.

### Look like the phone did

The scripts were written against the **mobile** sites, so:

- Use Playwright device emulation, e.g. `devices['Pixel 7']` (mobile Chrome UA,
  viewport, touch). Don't use the desktop layout.
- Use a **persistent context** (`chromium.launchPersistentContext(dir)`) on the
  PVC so cookies survive restarts. This is what keeps bot checks rare.
- Run Chromium **headed under Xvfb** (`xvfb-run`), not headless. Walmart and
  Target use PerimeterX ("robot or human" / `px-captcha`), which is much more
  likely to flag headless Chromium.
- Grant geolocation and set coordinates for the ZIP being checked (GameStop
  store lookup uses it): 45780 ≈ 39.369,-82.137 · 43130 ≈ 39.714,-82.599 ·
  45701 ≈ 39.329,-82.101.

### Solving a bot check / signing in (replaces "Show browser")

Add an optional **noVNC sidecar** sharing the Xvfb display. When a retailer is
stuck on a bot check, or to sign in to Amazon once (signed-in visits rarely get
a bot check, see the message in `StockChecker.onJobResult`):

```
kubectl -n zelda-stock port-forward deploy/zelda-stock-server 6080:6080
# open http://localhost:6080, solve it in the live browser, close the tab
```

Pause the scheduler while someone is using the browser
(`POST /api/pause`, `POST /api/resume`) so a run doesn't navigate away mid-solve.

### State and alert rules

Persist `state.json` on the PVC (no database needed):

```json
{ "lastRun": "2026-11-01T15:00:00Z", "rows": [ ... ],
  "inStockKeys": ["Walmart|<product>|<where>"],
  "unverifiedStreak": { "Walmart": 3 }, "slow": { "Amazon": { "at": "...", "rows": [ ... ] } } }
```

- Row key is `retailer|product|where` (same as `Results.key`).
- **In-stock alert**: push for each key that is `IN STOCK` now and wasn't on
  the previous run (same as `Results.saveAndNotify`). Re-alert if it goes out
  and comes back.
- **Blocked alert** (low priority): when a retailer has been
  `UNVERIFIED`/`ERROR` for 3 runs in a row, push once ("Walmart blocked, open
  the browser view"). Push "recovered" once when it reads normally again.
- **Heartbeat** (low priority): one push a day (e.g. 9:00 America/New_York)
  with "checked N times, nothing in stock" so you know the server is alive.
  Without this a dead pod looks the same as "no stock".

### HTTP API

Small HTTP server on port 8080 (Fastify or plain `node:http`):

| Method | Path | Purpose |
|---|---|---|
| GET | `/healthz` | liveness (process up) |
| GET | `/readyz` | readiness (browser launched) |
| GET | `/api/status` | `{ lastRun, nextRun, paused, rows[] }` for the app |
| POST | `/api/check` | run now (app's "Check now") |
| POST | `/api/test-alert` | send a fake in-stock push end to end |
| POST | `/api/pause`, `/api/resume` | for solving bot checks |

Everything under `/api/` requires `Authorization: Bearer $API_TOKEN`.

### Configuration (env, from ConfigMap/Secret)

| Var | Default | Notes |
|---|---|---|
| `ZIPS` | `45780 43130 45701` | |
| `RADIUS_MI` | `50` | |
| `INTERVAL_MIN` | `5` | with jitter |
| `SLOW_RETAILERS` | `Amazon:10,Target:10,Best_Buy:10` | at most every N min; last rows reused in between |
| `TZ` | `America/New_York` | heartbeat time |
| `DATA_DIR` | `/data` | profile + state.json |
| `PUSH` | `ntfy` | `fcm`, `ntfy`, or `fcm,ntfy` |
| `FCM_TOPIC` | `zelda-oot` | |
| `GOOGLE_APPLICATION_CREDENTIALS` | `/secrets/fcm/sa.json` | Secret |
| `NTFY_URL` / `NTFY_TOPIC` / `NTFY_TOKEN` | – | Secret, only for ntfy |
| `API_TOKEN` | – | Secret |

### Suggested layout

```
checks/                    shared retailer scripts (moved from android assets)
server/
  package.json             playwright, firebase-admin (or google-auth-library)
  Dockerfile
  src/index.js             config, scheduler loop, HTTP API
  src/browser.js           persistent context, bridge, runJob()
  src/jobs.js              job table above
  src/state.js             load/save state.json, diff → events
  src/notify/fcm.js        topic data messages via FCM HTTP v1
  src/notify/ntfy.js       POST to ntfy
deploy/k8s/                manifests below (kustomize)
.github/workflows/server.yml  build + push image to GHCR
```

### Container image

```dockerfile
# Pin to the current Playwright release; match the npm "playwright" version exactly.
FROM mcr.microsoft.com/playwright:v1.XX.X-noble
RUN apt-get update && apt-get install -y --no-install-recommends xvfb x11vnc novnc websockify \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY server/package*.json ./
RUN npm ci --omit=dev
COPY server/src ./src
COPY checks ./checks
USER pwuser
ENV DATA_DIR=/data CHECKS_DIR=/app/checks
CMD ["xvfb-run", "-a", "-s", "-screen 0 1080x2200x24", "node", "src/index.js"]
```

(If the noVNC sidecar is used, run Xvfb as its own process on `:99` instead of
`xvfb-run` so the sidecar can attach; set `DISPLAY=:99` in both.)

Build in GitHub Actions on pushes to `server/**` or `checks/**`, multi-arch
(`linux/amd64,linux/arm64` with `docker/build-push-action`, in case the cluster
has ARM nodes), push to `ghcr.io/alvis1337/zelda-stock-server:<sha>` and
`:latest`. If the package stays private, the cluster needs an
`imagePullSecret` for GHCR.

## Kubernetes

Namespace `zelda-stock`, managed with kustomize in `deploy/k8s/`.

- `namespace.yaml`
- `pvc.yaml`: 2Gi RWO for `/data` (Chromium profile + state). Use the
  cluster's default StorageClass.
- `configmap.yaml`: the non-secret env above.
- `secret.example.yaml`: template only, never commit real values. Create the
  real one by hand:
  ```
  kubectl -n zelda-stock create secret generic zelda-stock \
    --from-literal=API_TOKEN=$(openssl rand -hex 24) \
    --from-file=sa.json=./firebase-service-account.json
  ```
- `deployment.yaml`, the important parts:
  ```yaml
  replicas: 1
  strategy: { type: Recreate }          # one browser profile, RWO volume
  template:
    spec:
      securityContext: { fsGroup: 1001 }   # pwuser is 1001 in the noble image
      containers:
        - name: server
          image: ghcr.io/alvis1337/zelda-stock-server:latest
          envFrom: [{ configMapRef: { name: zelda-stock } }]
          env:
            - name: API_TOKEN
              valueFrom: { secretKeyRef: { name: zelda-stock, key: API_TOKEN } }
          ports: [{ containerPort: 8080 }]
          resources:
            requests: { cpu: 250m, memory: 768Mi }
            limits:   { memory: 2Gi }
          volumeMounts:
            - { name: data, mountPath: /data }
            - { name: fcm, mountPath: /secrets/fcm, readOnly: true }
            - { name: dshm, mountPath: /dev/shm }   # Chromium needs more than 64Mi shm
          livenessProbe:  { httpGet: { path: /healthz, port: 8080 }, periodSeconds: 30 }
          readinessProbe: { httpGet: { path: /readyz,  port: 8080 } }
      volumes:
        - { name: data, persistentVolumeClaim: { claimName: zelda-stock-data } }
        - { name: fcm, secret: { secretName: zelda-stock, items: [{ key: sa.json, path: sa.json }] } }
        - { name: dshm, emptyDir: { medium: Memory, sizeLimit: 512Mi } }
  ```
- `service.yaml`: ClusterIP on 8080 (and 6080 if noVNC sidecar).
- Not a CronJob: the scheduler lives in the pod so the browser and its
  cookies stay warm between runs, and dedup state is in one place.

Deploy:

```
kubectl apply -k deploy/k8s
kubectl -n zelda-stock rollout status deploy/zelda-stock-server
kubectl -n zelda-stock logs -f deploy/zelda-stock-server
kubectl -n zelda-stock port-forward svc/zelda-stock-server 8080:8080
curl -X POST -H "Authorization: Bearer $API_TOKEN" localhost:8080/api/test-alert
```

### Reaching the API from the phone (optional)

Alerts don't need this: FCM is outbound only. The app's status screen and
"Check now" do. Pick whatever the cluster already has, in this order:

1. **Tailscale** (operator or a sidecar) so only your devices can reach it.
2. **Cloudflare Tunnel** to a hostname, still behind `API_TOKEN`.
3. Ingress with TLS on a public hostname, behind `API_TOKEN`.

If none is set up, the app just shows the result carried in the last push.

## Push mechanism

| Option | Battery | Keeps loud alarm + auto-open checkout | Setup | Notes |
|---|---|---|---|---|
| **FCM topic, data message (recommended)** | ~zero: Google Play services already holds the connection | Yes, app builds the notification itself | Free Firebase project, `google-services.json` in the app, service account on the server | Outbound only. High-priority data messages wake the app in Doze. |
| ntfy (ntfy.sh or self-hosted) + ntfy app | Low with ntfy.sh (uses FCM); self-hosted needs ntfy's "instant delivery" socket | Partly: ntfy priority 5 is loud, but it can't open our checkout or use our alarm channel | One `curl` on the server, install ntfy app | Best as a quick start and as a backup channel. |
| UnifiedPush (via ntfy distributor) | Low | Yes | App gets a UnifiedPush connector, server posts to the endpoint the app registers | No Google dependency, but more moving parts and needs the app to register its endpoint with the server. |
| Gotify | Socket in the Gotify app, more battery | No | Self-host | Not worth it here. |

**Recommendation:** FCM with **topic messaging**. The app calls
`subscribeToTopic("zelda-oot")` once, and the server sends to the topic, so
there's no device-registration endpoint and no inbound exposure. Send
**data-only** messages with `android.priority = "high"` so `onMessageReceived`
always runs and the app can show the same insistent alarm with a full-screen
intent into `CheckoutActivity`. Also post the same alert to an **ntfy.sh topic**
as a backup (one HTTP POST, `Priority: 5`, `Click: <url>`), which also works on
day one before the app is changed.

FCM message the server sends (HTTP v1, `projects/<id>/messages:send`):

```json
{ "message": {
  "topic": "zelda-oot",
  "android": { "priority": "high", "ttl": "300s" },
  "data": {
    "type": "in_stock",            // in_stock | blocked | recovered | heartbeat | test
    "retailer": "Walmart",
    "product": "Nintendo Switch 2 – The Legend of Zelda 40th Anniversary Edition",
    "where": "online",
    "detail": "Sold and shipped by Walmart.com",
    "url": "https://www.walmart.com/ip/21002656445",
    "key": "Walmart|...|online",
    "checkedAt": "2026-11-01T15:00:00Z"
  } } }
```

FCM data values must be strings. Keep `ttl` short: a stale in-stock alert an
hour later is noise.

## Android app changes (thin client)

Remove (this is the battery drain):

- `WatchService`, `BootReceiver`, the exact alarm, wake lock, and foreground
  service. Delete their manifest entries.
- Permissions no longer needed: `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`,
  `SCHEDULE_EXACT_ALARM`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
  `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`.
- UI: the watcher toggle, interval picker, battery-optimization and
  exact-alarm "Allow" rows, location switch, `LocationHelper` (ZIPs are now
  server config).
- `StockChecker` and the offscreen WebView. Optionally keep a manual
  "Check on this phone" button as a fallback when the server is down; it costs
  nothing unless tapped.

Add:

- Firebase: `com.google.gms.google-services` plugin, `firebase-bom` +
  `firebase-messaging`. Keep `google-services.json` out of git: store it as a
  `GOOGLE_SERVICES_JSON` repo secret and write it to `android/app/` in
  `android.yml` before the build.
- `PushService extends FirebaseMessagingService`, registered in the manifest
  with the `com.google.firebase.MESSAGING_EVENT` intent filter.
  `onMessageReceived` switches on `data.type`:
  - `in_stock` / `test`: build the row and call the existing
    `Results.notifyInStock` (alarm channel, `FLAG_INSISTENT`, full-screen
    intent into `CheckoutActivity` with assist on). Make it non-private or add
    a public wrapper.
  - `blocked` / `recovered` / `heartbeat`: a normal-priority notification on a
    new `server` channel.
  - Also save the row to prefs so the main screen can show it offline.
- On launch: `FirebaseMessaging.getInstance().subscribeToTopic("zelda-oot")`.
- Settings: server URL + API token (only for status/"Check now").
- Main screen: on open, `GET /api/status` and render rows with the existing
  results list; "Check now" calls `POST /api/check` then refreshes. Show
  "Last server check: 3 min ago" prominently, red if older than 30 min.

Keep as is:

- `CheckoutActivity` + `assist.js`: signing in and adding to cart has to
  happen on the phone with your own cookies.
- The alarm channel, "Test alert" button, and full-screen-intent "Allow" row.
- `Updater`, but call it on app open instead of from the watcher loop.

## Rollout plan

1. **Server + ntfy only.** Build the server, deploy, set `PUSH=ntfy`, install
   the ntfy app and subscribe to the topic. Turn the phone watcher off. Battery
   problem is solved at this point.
2. **Verify.** For a day, compare `/api/status` with a manual "Check now" on
   the phone. Every retailer should read `OUT OF STOCK` (not `UNVERIFIED`) from
   the server. Fix any that are blocked (noVNC, Amazon sign-in, headed mode).
   Use `/api/test-alert` with the phone locked to confirm it rings.
3. **Thin client.** Ship the app changes with FCM, set `PUSH=fcm,ntfy`, test
   alert with the phone locked: it should ring and open checkout.
4. **Clean up.** Remove the old watcher code, and remove the `schedule:` from
   `.github/workflows/stock-check.yml` (runners are blocked anyway).

## Open items

- Best Buy: the server reads the product page like the app does, so it doesn't
  need the pending API key. `zelda_stock.py` still does.
- Confirm the cluster's CPU architecture and StorageClass before building.
- Decide how the phone reaches the API (Tailscale vs Cloudflare Tunnel), or skip it.

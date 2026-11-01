# Phone test plan: Zelda Stock app against the live server

Start it with the prompt in [prompt.md](prompt.md). For a Claude Code session on the Mac with the Android phone plugged in over USB
(`adb`) and `kubectl` access to the home cluster. Run every test below, collect the
evidence, and write the report described at the end. You test and report; you
don't fix code. If a test can't run, mark it BLOCKED and say why.

## Ground rules

- **Never buy anything.** Don't tap Place order, Buy now, Pay or Checkout. After
  each add-to-cart test, remove the item from that retailer's cart.
- Change server state only with the `deploy/zs-api.sh` calls listed here.
- If you change the app's Server settings (T12), put the correct values back
  before moving on.
- Restore anything you change on the phone (airplane mode, Doze, screen timeout).
- Take a screenshot for every step marked 📸 (see Tools). Name the files
  `T<n>-<k>-<what>.png`, for example `T9-2-checkout-over-lockscreen.png`.

## Setup

```bash
git pull                                      # repo root, main
adb devices                                   # exactly one device, "device" state
deploy/zs-api.sh GET /api/status              # JSON with lastRun, rows, pushDevices
adb shell dumpsys package me.chrisalvis.zeldastock | grep -E "versionName|versionCode"
adb shell pm list packages | grep -E "zeldastock|io.heckel.ntfy"
```

The app must be **v1.0.28 or newer** (Settings > app, or the line above). If it's
older, open the app and use its "Download and install" update banner. If ntfy
(`io.heckel.ntfy`) is missing, stop and report BLOCKED: it's the push distributor.

## Tools

```bash
# Screenshot
adb exec-out screencap -p > docs/test-reports/<run>/T1-1-home.png
# UI tree, to find text or tap targets (then: adb shell input tap X Y)
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml /tmp/ui.xml
# App log (clear first with: adb logcat -c)
adb logcat -d -v time | grep -iE "zeldastock|unifiedpush|AndroidRuntime" | tail -100
# Notifications the app has posted
adb shell dumpsys notification --noredact | grep -A25 "pkg=me.chrisalvis.zeldastock"
# Screen: off / on / unlock (no PIN on the test device, or unlock by hand and say so)
adb shell input keyevent KEYCODE_SLEEP; adb shell input keyevent KEYCODE_WAKEUP; adb shell input keyevent 82
# Open the app
adb shell monkey -p me.chrisalvis.zeldastock -c android.intent.category.LAUNCHER 1
```

Timing: when a test asks for latency, note the time you sent the API call and the
time the notification appeared (`dumpsys notification` shows `when=`/post time,
or poll the UI every second).

## Tests

### T1 Install, version, permissions
1. Record device model, Android version (`adb shell getprop ro.build.version.release`),
   app versionName, ntfy installed yes/no.
2. Notification permission: `adb shell dumpsys package me.chrisalvis.zeldastock | grep POST_NOTIFICATIONS`.
3. Full-screen intent: `adb shell appops get me.chrisalvis.zeldastock USE_FULL_SCREEN_INTENT`.
4. Confirm the old watcher is gone: `adb shell dumpsys alarm | grep -c zeldastock` and
   `adb shell dumpsys activity services me.chrisalvis.zeldastock` (expect 0 alarms and
   no running services except, briefly, the push receiver).

### T2 Main screen against the server
1. Open the app. 📸 the top, then scroll and 📸 until the whole screen is captured.
2. Note "Last server check: N min ago" (should be green and under 10 min) and the
   result cards.
3. `deploy/zs-api.sh GET /api/status`: compare `lastRun` and every row's
   retailer/status/where with the cards. List any mismatch.

### T3 Push registration
1. The Alerts card should say "On. The server pushes alerts to this phone through …". 📸
2. `deploy/zs-api.sh GET /api/status`: `pushDevices` must be ≥ 1.
3. If it isn't on: tap "Set up alerts", follow any ntfy prompt, 📸 each step, recheck.

### T4 Check now
1. Note `lastRun` from the API. Tap **Check now**. 📸 while it says "Checking …".
2. Wait for it to finish (≤ 4 min). 📸 the finished state. The API's `lastRun` must
   be newer and the button usable again. Record the duration.

### T5 Local alarm
1. Tap **Test alarm**. Record: sound yes/no, vibration yes/no, keeps ringing until
   dismissed (insistent) yes/no. 📸 the notification shade.
2. Dismiss it.

### T6 Server test alert, phone unlocked
1. App in the background, screen on.
2. `deploy/zs-api.sh POST /api/test-alert` and record the response (`sent.up` must be `ok`).
3. Record latency until the notification appears. 📸 the shade.
4. Tap it: Checkout should open on the Best Buy page **without** adding anything
   (status bar text "sign in or browse"). 📸 Close it.

### T7 Server test alert, screen off
1. `adb shell input keyevent KEYCODE_SLEEP`, wait 60 s.
2. Send the test alert as in T6. Record latency, whether the screen woke, and
   whether it rang. Wake and 📸 the lock screen.

### T8 Doze
1. `adb shell dumpsys battery unplug; adb shell dumpsys deviceidle force-idle`
   (screen off first).
2. Send the test alert. Record whether and when it arrives.
3. Restore: `adb shell dumpsys deviceidle unforce; adb shell dumpsys battery reset`.

### T9 Simulated restock with checkout assist (the important one)
Pick a cheap item (under $30), **in stock online and sold by the retailer
itself**, at Best Buy and at Target. Get its product URL from the retailer's site.
For each retailer:
1. Phone screen off, locked.
2. `deploy/zs-api.sh POST /api/test-alert '{"kind":"in_stock","retailer":"Best Buy","product":"<item name>","url":"<product url>"}'`
3. Expected: the alarm rings and Checkout opens **over the lock screen** by itself
   (full-screen intent). 📸 as soon as it shows.
4. Watch the dark status bar: "checking price and seller…", "Adding to cart ($x)…",
   "Added. Opening cart". It should end on the cart with the item in it. 📸 each
   status and the cart. Record the total time from the API call to the cart.
5. If it's not signed in, note that. It should still add to the cart as a guest.
6. **Remove the item from the cart.** 📸 the empty cart.

### T10 Checkout guards (must refuse)
Same call as T9, phone unlocked, with:
1. A Best Buy item over $560 sold by Best Buy (e.g. a TV). Expected status: "Price
   shows $… (over $560). Not adding". 📸 Confirm the cart is unchanged.
2. A Walmart **marketplace** listing (third-party seller in the buy box), retailer
   "Walmart". Expected: "Sold by …, not Walmart. Not adding" (or the price guard).
   📸 Confirm the cart is unchanged.

### T11 Server status notifications
Send, about 5 s apart:
```bash
deploy/zs-api.sh POST /api/test-alert '{"kind":"blocked","retailer":"Walmart"}'
deploy/zs-api.sh POST /api/test-alert '{"kind":"blocked","retailer":"Target"}'
deploy/zs-api.sh POST /api/test-alert '{"kind":"recovered","retailer":"Walmart"}'
deploy/zs-api.sh POST /api/test-alert '{"kind":"heartbeat"}'
```
Expected in the shade: Target blocked, Walmart recovered (it replaced Walmart
blocked), and the heartbeat. These are normal-priority, not the alarm. 📸 the
shade and paste the `dumpsys notification` lines for the app.

### T12 Wrong credentials, then restore
1. Get the right values first:
   `kubectl -n zelda-stock get secret zelda-stock-access -o jsonpath='{.data.CF_ACCESS_CLIENT_ID}' | base64 --decode`
   (and `CF_ACCESS_CLIENT_SECRET`).
2. Server settings > Edit: change one character of the secret, **Save and connect**.
   Expected: "Could not connect: Cloudflare Access refused …". 📸
3. Re-enter the correct secret, Save and connect, confirm it connects. 📸

### T13 Offline
1. Airplane mode on (`adb shell cmd connectivity airplane-mode enable`).
2. Open the app, tap Refresh. Expected: no crash, last results still shown, an
   error line about reaching the server. 📸
3. Airplane mode off (`... airplane-mode disable`). Refresh works again.

### T14 Battery: the reason for all this
The phone stays on USB. `dumpsys battery unplug` makes Android treat it as on
battery (batterystats counts, Doze works) while it charges. This test measures the
app's wakeups, wakelocks, alarms and CPU time, not mAh, so charging doesn't matter.
1. `adb shell dumpsys battery unplug` (required), then `adb shell dumpsys batterystats --reset`.
   Screen off, and leave the phone alone for **at least 60 minutes**.
2. Then:
   `adb shell dumpsys batterystats me.chrisalvis.zeldastock > docs/test-reports/<run>/T14-batterystats.txt`
   and `adb shell dumpsys batterystats | grep -iA3 "zeldastock" | head -60`.
3. Report the app's wakelocks, alarms, wakeups and CPU time for the window.
   Expected: next to nothing (only push-receiver wakeups if alerts were sent).
   For contrast, note the same numbers for the ntfy app (`io.heckel.ntfy`).
4. Restore: `adb shell dumpsys battery reset`.

### T15 Fast checkout sign-in pages
Tap each store button under Fast checkout (Best Buy, Target, Walmart, Amazon,
Nintendo). For each: the page loads, and whether it's already signed in. 📸 each.
Don't sign in or change anything.

### T16 Anything that looks wrong
Clipped text, overlapping views, wrong colours, confusing wording, crashes
(`adb logcat -d | grep -A20 "FATAL EXCEPTION"`), slow screens. 📸 each.

## Report

Write `docs/test-reports/phone-<YYYY-MM-DD>.md`, put the screenshots and text
dumps in `docs/test-reports/phone-<YYYY-MM-DD>/`, then commit and push to main:

```bash
git add docs/test-reports && git commit -m "Phone test report <YYYY-MM-DD>" && git push
```

Report layout:

```markdown
# Phone test report <YYYY-MM-DD>

Device: <model>, Android <n>; app v<x>; ntfy <version>; server lastRun at start <ISO>

| Test | Result | Key numbers / one-line note |
|---|---|---|
| T1 | PASS / FAIL / BLOCKED | ... |
...

## Failures and surprises
<one block per FAIL or oddity: what you did, what you expected, what happened,
evidence file names, logcat excerpt (≤ 30 lines)>

## T1 ... T16
<per test: steps actually run, observations, numbers (latency, durations),
evidence file names>
```

Keep results factual: quote the app's exact status text, and give times in seconds.

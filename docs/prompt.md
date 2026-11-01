# Prompt for the phone-testing session

Paste the block below into a Claude Code session on the Mac, opened in this repo,
with the phone plugged in over USB. The test plan it follows is
[phone-test-plan.md](phone-test-plan.md).

Before starting:

- The first adb connection shows "Allow USB debugging?" on the phone. Accept it.
- If the phone has a PIN, the session can't unlock it after the screen-off tests.
  Remove the PIN for the run, or be ready to unlock it when asked.

```
You're testing the Zelda Stock Android app on my phone, which is plugged in over USB (use adb). You also have kubectl access to my home cluster, where the app's server runs.

1. Run `git pull` in this repo (branch main).
2. Read docs/phone-test-plan.md and follow it exactly: setup, then tests T1 to T16, then the report.
3. Hard rules:
   - Never place an order. Never tap Place order, Buy now, Pay or Checkout.
   - Remove every test item from the carts.
   - Put any setting you change back the way it was.
   - Only touch the server through the deploy/zs-api.sh calls listed in the plan.
4. You are testing and reporting, not fixing. Don't change any code. If a test can't run, mark it BLOCKED, say why, and keep going.
5. T9 and T10 need product URLs. Find them yourself on the retailers' sites:
   - T9: a cheap item (under $30), in stock online and sold by the retailer itself. One at Best Buy, one at Target.
   - T10: a Best Buy item over $560, and a Walmart listing sold by a marketplace seller.
6. T14 is the battery test. The phone stays plugged in; the plan uses `dumpsys battery unplug` so Android counts it as on battery. Start it once T1 to T13 are done, leave the phone alone for 60 minutes, then do T15 and T16.
7. When you're done, commit the report and screenshots under docs/test-reports/ and push to main.
8. Reply with the report's file path and the summary table.

Don't stop to ask me questions unless the phone is locked with a PIN you can't get past or adb loses the device. Otherwise make a sensible call and note it in the report.
```

When the report is pushed, tell the server-side session "report's in".

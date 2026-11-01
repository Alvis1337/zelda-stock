# zelda-stock

Checks Best Buy, Target, GameStop and Walmart for Legend of Zelda Switch 2 stock
(40th Anniversary Edition console, BotW / TotK Switch 2 Editions) near given ZIPs.

Only reports IN STOCK when the retailer's own response says so; blocks and
ambiguous responses are reported as UNVERIFIED.

- Run locally: `python3 zelda_stock.py --zip 45701 45780 43130`
- GitHub Actions: "Zelda stock check" workflow, manual only (the home server
  replaced the schedule). Results appear in the run summary.
- Best Buy needs a free API key from developer.bestbuy.com, stored as the
  `BESTBUY_API_KEY` repo secret.

## Moving the checks off the phone

The checks run on the home k8s cluster (`server/`, `deploy/k8s/`). The Android
app no longer checks anything itself: it shows the server's results, gets alerts
through UnifiedPush (the ntfy app delivers them) and does the checkout. Setup and
operations are in [server/README.md](server/README.md). The retailer scripts live
in `checks/`.

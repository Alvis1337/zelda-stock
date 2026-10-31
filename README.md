# zelda-stock

Checks Best Buy, Target, GameStop and Walmart for Legend of Zelda Switch 2 stock
(40th Anniversary Edition console, BotW / TotK Switch 2 Editions) near given ZIPs.

Only reports IN STOCK when the retailer's own response says so; blocks and
ambiguous responses are reported as UNVERIFIED.

- Run locally: `python3 zelda_stock.py --zip 45701 45780 43130`
- GitHub Actions: "Zelda stock check" workflow, manual or every 30 minutes.
  Results appear in the run summary.
- Best Buy needs a free API key from developer.bestbuy.com, stored as the
  `BESTBUY_API_KEY` repo secret.

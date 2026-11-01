// The job table, mirroring StockChecker.run in the Android app.

export const AMAZON = 'Amazon';

/** Jobs for one run. `zips` is every ZIP, `includeAmazon` false reuses its last rows. */
export function buildJobs(zips, radius, includeAmazon) {
  const all = { zips, radius };
  const jobs = [
    { retailer: 'Best Buy', url: 'https://www.bestbuy.com/site/6691841.p?skuId=6691841',
      script: 'bestbuy.js', params: all, zip: zips[0] },
    // target.js loops over every ZIP itself.
    { retailer: 'Target', url: 'https://www.target.com/s?searchTerm=nintendo+switch+2+zelda+40th+anniversary+edition',
      script: 'target.js', params: all, zip: zips[0] },
    { retailer: 'Walmart', url: 'https://www.walmart.com/ip/21002656445',
      script: 'walmart.js', params: all, zip: zips[0] },
    { retailer: 'GameStop', url: 'https://www.gamestop.com/search/?q=switch+2+zelda+40th+anniversary&lang=default',
      script: 'gamestop.js', params: all, zip: zips[0] },
    // gamestop_store.js only reads zips[0], so it runs once per ZIP.
    ...zips.map((z) => ({
      retailer: 'GameStop',
      url: 'https://www.gamestop.com/consoles-hardware/nintendo-switch-2/products/nintendo-switch-2-the-legend-of-zelda-40th-anniversary-edition/20037854.html',
      script: 'gamestop_store.js', params: { zips: [z], radius }, zip: z,
    })),
    { retailer: 'Nintendo', url: 'https://www.nintendo.com/us/store/products/nintendo-switch-2-the-legend-of-zelda-40th-anniversary-edition-121642/',
      script: 'nintendo.js', params: all, zip: zips[0] },
  ];
  if (includeAmazon) {
    jobs.push({ retailer: AMAZON, url: 'https://www.amazon.com/dp/B0HJ6F8L6V',
      script: 'amazon.js', params: all, zip: zips[0] });
  }
  return jobs;
}

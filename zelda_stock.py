#!/usr/bin/env python3
"""
Zelda Switch 2 stock checker: Best Buy, Target, GameStop, Walmart.

Checks online and nearby-store availability for:
  - Nintendo Switch 2 - The Legend of Zelda 40th Anniversary Edition (console, $519.99, no game)
  - Zelda: Breath of the Wild - Nintendo Switch 2 Edition (game)
  - Zelda: Tears of the Kingdom - Nintendo Switch 2 Edition (game)

Rule: a result is only printed as IN STOCK when the retailer's own response says
so (positive quantity or an explicit in-stock flag). Blocks, errors and anything
ambiguous are printed as UNVERIFIED, never guessed.

Usage (stdlib only, Python 3.8+):
  python3 zelda_stock.py                          # default ZIPs: Athens, The Plains, Lancaster OH
  python3 zelda_stock.py --zip 45701 43130 --radius 40
  BESTBUY_API_KEY=xxxx python3 zelda_stock.py     # enables Best Buy (free key: developer.bestbuy.com)
  python3 zelda_stock.py --target-tcin 12345678   # add more Target item ids
  python3 zelda_stock.py --debug                  # dump raw responses when parsing fails
"""
import argparse
import gzip
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_ZIPS = ["45701", "45780", "43130"]  # Athens, The Plains, Lancaster (OH)
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/130.0 Safari/537.36")

# Target TCINs seen in search results for Zelda Switch 2 items. Names are
# resolved from Target's response, not assumed here.
TARGET_TCINS = ["1013322047", "1013559065"]
# Public key used by target.com's own web front end for its redsky API.
TARGET_KEY = "9f36aeafbe60771e321a7cc95a78140772ab3e96"

GAMESTOP_PAGES = {
    "BotW - Switch 2 Edition": "https://www.gamestop.com/425554",
    "TotK - Switch 2 Edition": "https://www.gamestop.com/424591",
}

DEBUG = False
results = []  # (retailer, product, where, status, detail, url)


def log_debug(label, body):
    if DEBUG:
        print(f"\n--- DEBUG {label} ---\n{str(body)[:3000]}\n", file=sys.stderr)


def fetch(url, headers=None, timeout=20):
    """Return (status_code, text). status_code is None on network failure."""
    h = {"User-Agent": UA, "Accept": "application/json,text/html;q=0.9,*/*;q=0.8",
         "Accept-Language": "en-US,en;q=0.9", "Accept-Encoding": "gzip"}
    h.update(headers or {})
    req = urllib.request.Request(url, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            if r.headers.get("Content-Encoding") == "gzip":
                raw = gzip.decompress(raw)
            return r.status, raw.decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        try:
            body = e.read().decode("utf-8", "replace")
        except Exception:
            body = ""
        return e.code, body
    except Exception as e:  # DNS, timeout, TLS, proxy
        return None, f"{type(e).__name__}: {e}"


def looks_blocked(code, text):
    if code in (401, 403, 412, 429, 503) or code is None:
        return True
    t = text[:5000].lower()
    return any(s in t for s in ("captcha", "px-captcha", "access denied",
                                "robot or human", "are you a human", "akamai"))


def add(retailer, product, where, status, detail="", url=""):
    results.append((retailer, product, where, status, detail, url))


# ---------------------------------------------------------------- Best Buy
def check_bestbuy(zips, radius):
    key = os.environ.get("BESTBUY_API_KEY")
    if not key:
        add("Best Buy", "-", "-", "SKIPPED",
            "set BESTBUY_API_KEY (free at developer.bestbuy.com) to enable")
        return
    q = "(search=zelda&search=switch&search=2)"
    url = (f"https://api.bestbuy.com/v1/products{urllib.parse.quote(q, safe='()=&')}"
           f"?apiKey={key}&format=json&pageSize=50"
           "&show=sku,name,salePrice,onlineAvailability,inStoreAvailability,url")
    code, text = fetch(url)
    if code != 200:
        add("Best Buy", "search", "-", "UNVERIFIED", f"HTTP {code}: {text[:120]}")
        return
    products = json.loads(text).get("products", [])
    # Keep Switch 2 items only; Best Buy names them "Nintendo Switch 2 ..."
    products = [p for p in products if "switch 2" in p["name"].lower()
                and "zelda" in p["name"].lower()]
    if not products:
        add("Best Buy", "search", "-", "NONE FOUND", "no Zelda Switch 2 SKUs returned")
    for p in products:
        name, sku = p["name"], p["sku"]
        add("Best Buy", name, "online",
            "IN STOCK" if p.get("onlineAvailability") else "OUT OF STOCK",
            f"${p.get('salePrice')}", p.get("url", ""))
        for z in zips:
            surl = (f"https://api.bestbuy.com/v1/products/{sku}/stores.json"
                    f"?postalCode={z}&apiKey={key}")
            c, t = fetch(surl)
            if c != 200:
                add("Best Buy", name, f"stores near {z}", "UNVERIFIED", f"HTTP {c}")
                continue
            stores = json.loads(t).get("stores", [])
            near = [s for s in stores if float(s.get("distance", 999)) <= radius]
            if near:
                for s in near:
                    add("Best Buy", name, f"{s.get('name')} ({s.get('city')}, "
                        f"{s.get('distance')} mi)", "IN STOCK",
                        f"{s.get('address')}, pickup today", p.get("url", ""))
            else:
                add("Best Buy", name, f"stores within {radius} mi of {z}",
                    "OUT OF STOCK", "no store reports pickup availability")


# ------------------------------------------------------------------ Target
def check_target(zips, radius, tcins):
    for tcin in tcins:
        name = f"TCIN {tcin}"
        purl = f"https://www.target.com/p/-/A-{tcin}"
        for z in zips:
            url = ("https://redsky.target.com/redsky_aggregations/v1/web/fiats_v1"
                   f"?key={TARGET_KEY}&tcin={tcin}&nearby={z}&radius={radius}"
                   "&limit=20&include_only_available_stores=false&requested_quantity=1")
            code, text = fetch(url, {"Origin": "https://www.target.com",
                                     "Referer": purl})
            if looks_blocked(code, text) or code != 200:
                add("Target", name, f"stores near {z}", "UNVERIFIED",
                    f"HTTP {code} (blocked or changed API)", purl)
                log_debug(f"target {tcin} {z}", text)
                continue
            try:
                data = json.loads(text)["data"]["fulfillment_fiats"]
                locs = data.get("locations", [])
                prod = data.get("product") or {}
                desc = (prod.get("item", {}).get("product_description", {})
                        .get("title"))
                if desc:
                    name = desc
            except (KeyError, TypeError, ValueError):
                add("Target", name, f"stores near {z}", "UNVERIFIED",
                    "unexpected response shape (run --debug)", purl)
                log_debug(f"target {tcin} {z}", text)
                continue
            found = False
            for loc in locs:
                qty = loc.get("location_available_to_promise_quantity") or 0
                store = loc.get("store", {})
                sname = store.get("location_name", loc.get("location_id"))
                dist = loc.get("distance")
                if qty and qty > 0:
                    found = True
                    add("Target", name, f"{sname} ({dist} mi)", "IN STOCK",
                        f"qty {int(qty)}", purl)
            if not found:
                add("Target", name, f"{len(locs)} stores within {radius} mi of {z}",
                    "OUT OF STOCK", "0 available at every store returned", purl)


# ---------------------------------------------------------------- GameStop
def jsonld_availability(html):
    for block in re.findall(r'<script[^>]+application/ld\+json[^>]*>(.*?)</script>',
                            html, re.S):
        try:
            data = json.loads(block)
        except ValueError:
            continue
        for item in data if isinstance(data, list) else [data]:
            offers = item.get("offers") if isinstance(item, dict) else None
            if isinstance(offers, dict):
                offers = [offers]
            for o in offers or []:
                av = str(o.get("availability", ""))
                if av:
                    return av.rsplit("/", 1)[-1], o.get("price")
    return None, None


def check_gamestop():
    for name, url in GAMESTOP_PAGES.items():
        code, text = fetch(url, {"Accept": "text/html"})
        if looks_blocked(code, text) or code != 200:
            add("GameStop", name, "online", "UNVERIFIED", f"HTTP {code} (blocked)", url)
            continue
        av, price = jsonld_availability(text)
        if av == "InStock":
            add("GameStop", name, "online", "IN STOCK", f"${price}", url)
        elif av in ("OutOfStock", "SoldOut", "Discontinued"):
            add("GameStop", name, "online", "OUT OF STOCK", f"${price}", url)
        else:
            add("GameStop", name, "online", "UNVERIFIED",
                f"page gave no clear availability ({av})", url)
            log_debug(f"gamestop {name}", text)
    add("GameStop", "-", "store pickup", "MANUAL",
        "GameStop has no public store API; use 'Check other stores' on the product page")


# ----------------------------------------------------------------- Walmart
def check_walmart(zips):
    url = ("https://www.walmart.com/search?q="
           + urllib.parse.quote("zelda nintendo switch 2"))
    code, text = fetch(url, {"Accept": "text/html"})
    if looks_blocked(code, text) or code != 200:
        add("Walmart", "search", "online", "UNVERIFIED",
            f"HTTP {code} (Walmart bot protection)", url)
        return
    m = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', text, re.S)
    if not m:
        add("Walmart", "search", "online", "UNVERIFIED", "no product data in page", url)
        return
    blob = m.group(1)
    seen = set()
    # Pull item objects that carry name + availabilityStatus + sellerName.
    for im in re.finditer(r'\{[^{}]*"name":"([^"]*[Zz]elda[^"]*)"[^{}]*\}', blob):
        obj = im.group(0)
        pname = im.group(1)
        if "switch 2" not in pname.lower() or pname in seen:
            continue
        seen.add(pname)
        status = re.search(r'"availabilityStatusV2":\{"display":"([^"]+)"', obj) \
            or re.search(r'"availabilityStatus":"([^"]+)"', obj)
        seller = re.search(r'"sellerName":"([^"]+)"', obj)
        price = re.search(r'"price":([\d.]+)', obj)
        st = status.group(1) if status else "unknown"
        sold_by = seller.group(1) if seller else "unknown seller"
        verdict = "IN STOCK" if st.upper().replace(" ", "_") == "IN_STOCK" else (
            "OUT OF STOCK" if "out" in st.lower() else "UNVERIFIED")
        add("Walmart", pname, "online", verdict,
            f"{st}, sold by {sold_by}, ${price.group(1) if price else '?'}"
            + ("" if sold_by.lower().startswith("walmart") else " (3rd party, check price)"),
            url)
    if not seen:
        add("Walmart", "search", "online", "NONE FOUND", "no Zelda Switch 2 items parsed", url)


# -------------------------------------------------------------------- main
def main():
    global DEBUG
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", nargs="+", default=DEFAULT_ZIPS)
    ap.add_argument("--radius", type=int, default=50, help="miles")
    ap.add_argument("--target-tcin", nargs="*", default=[])
    ap.add_argument("--debug", action="store_true")
    a = ap.parse_args()
    DEBUG = a.debug

    for fn, args in ((check_bestbuy, (a.zip, a.radius)),
                     (check_target, (a.zip, a.radius, TARGET_TCINS + a.target_tcin)),
                     (check_gamestop, ()),
                     (check_walmart, (a.zip,))):
        try:
            fn(*args)
        except Exception as e:
            add(fn.__name__.replace("check_", "").title(), "-", "-", "ERROR",
                f"{type(e).__name__}: {e}")

    order = {"IN STOCK": 0, "OUT OF STOCK": 1, "NONE FOUND": 2, "UNVERIFIED": 3,
             "MANUAL": 4, "SKIPPED": 5, "ERROR": 6}
    results.sort(key=lambda r: (order.get(r[3], 9), r[0]))
    print(f"\nZelda Switch 2 stock check, ZIPs {', '.join(a.zip)}, radius {a.radius} mi\n")
    for r, p, w, s, d, u in results:
        print(f"[{s:12}] {r:8} | {p[:60]:60} | {w}")
        if d:
            print(f"{'':15}{d}")
        if u:
            print(f"{'':15}{u}")
    hits = [r for r in results if r[3] == "IN STOCK"]
    print(f"\n{len(hits)} verified in-stock result(s).")
    return 0 if hits else 1


if __name__ == "__main__":
    sys.exit(main())

"""Compare an independent single-store POS export with the running guide's read-only offers API."""
import argparse
import csv
from decimal import Decimal, InvalidOperation
from datetime import date
import http.cookiejar
import json
import os
from pathlib import Path
import urllib.parse
import urllib.request


def compare(row, offer):
    result = dict(store=row["store"], sku=row["sku"], snapshot=row["snapshot"],
                  actualSnapshot=offer["snapshot"], source=offer["source"], queriedAt=offer["queriedAt"])
    if offer["sku"] != row["sku"] or offer["snapshot"] != row["snapshot"]:
        return dict(result, status="NOT_COMPARABLE")
    result["expected"] = dict(price_cny=row["price_cny"],available_packs=int(row["available_packs"]))
    result["actual"] = dict(price_cny=str(offer["price"]),available_packs=offer["packs"])
    differences = []
    for field, key in [("price_cny", "price"), ("available_packs", "packs")]:
        expected = Decimal(row[field])
        actual = Decimal(str(offer[key]))
        if expected != actual:
            differences.append(dict(field=field, expected=str(expected), actual=str(actual),
                                    actualMinusExpected=str(actual - expected)))
    return dict(result,status="DIFFERENT" if differences else "MATCH",differences=differences)


def load_reference(path):
    with path.open(encoding="utf-8-sig",newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        raise ValueError("对账快照为空")
    batch = (rows[0]["store"],rows[0]["snapshot"])
    date.fromisoformat(batch[1])
    seen = set()
    for row in rows:
        price = Decimal(row["price_cny"])
        if (not batch[0] or (row["store"],row["snapshot"]) != batch or not row["sku"] or row["sku"] in seen
                or not row["available_packs"].isascii() or not row["available_packs"].isdigit()
                or not price.is_finite() or price < 0 or price != price.quantize(Decimal(".01"))):
            raise ValueError("快照必须属于同一门店和业务日期，SKU 唯一，金额和库存有效")
        seen.add(row["sku"])
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference",type=Path,nargs="?",default=Path("fixtures/pos/direct-reference.csv"))
    parser.add_argument("--port",type=int,default=18136)
    parser.add_argument("--user",default="direct")
    args = parser.parse_args()
    rows = load_reference(args.reference)
    base = f"http://127.0.0.1:{args.port}"
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def read(path):
        with client.open(base+path,timeout=20) as response:
            return json.load(response,parse_float=Decimal)

    token = read("/api/session")["csrf"]
    login = urllib.parse.urlencode(dict(username=args.user,password=os.getenv("BABYCARE_DEMO_PASSWORD","BabyCare-demo")))
    request = urllib.request.Request(base+"/api/session/login",login.encode(),{"X-CSRF-TOKEN":token})
    with client.open(request,timeout=20):
        pass
    store = read("/api/session")["store"]["id"]
    if store != rows[0]["store"]:
        raise ValueError("登录门店与参照快照不一致")
    results = []
    for row in rows:
        try:
            sku = urllib.parse.quote(row["sku"],safe="")
            results.append(compare(row,read(f"/api/stores/{store}/offers/{sku}")))
        except (OSError, KeyError, ValueError) as exc:
            results.append(dict(store=store,sku=row["sku"],status="UNAVAILABLE",error=str(exc)))
    print(json.dumps(dict(reference=str(args.reference),rows=results),ensure_ascii=False,indent=2))
    return 0 if all(r["status"]=="MATCH" for r in results) else 1


if __name__=="__main__":
    try:
        raise SystemExit(main())
    except (OSError,ValueError,KeyError,InvalidOperation) as error:
        raise SystemExit(str(error))

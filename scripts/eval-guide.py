"""Run the real Bailian guide against eight isolated demo conversations (Python 3.9+)."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "target/guide-eval.json"
LIST = "本店有哪些 L 码纸尿裤？只看芽禾和绒朵。"
QUOTE = "第二款买两包，今天有活动吗？如果拆了一包还能退吗？"
HANDOFF = "帮我把第二款的拆包问题交给售后"
CASES = [
    ("改选", "刚才说第二款，改第一款，也是两包。"),
    ("改选", "那换回列表最前面那个，两包算下来多少？"),
    ("联查", "排后面那个拿两包多少钱？拆了一包还能退吗？"),
    ("联查", "第二个买两包按活动算多少，已经拆包能不能退？"),
    ("消歧", "这款拆了，帮我找售后。"),
    ("消歧", "这个拆开了，帮我交给售后看一下。"),
    ("取消", "先别提交，我只是想看看规定。"),
    ("取消", "不用转人工了，给我看一下拆了一包还能不能退的条款。"),
]


class Session:
    def __init__(self, url, user, password):
        self.url = url
        self.client = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.token = self.call("/api/session")["csrf"]
        self.call("/api/session/login", urllib.parse.urlencode(dict(username=user,password=password)).encode())
        self.token = self.call("/api/session")["csrf"]

    def call(self, path, body=None):
        headers = {}
        if body is not None:
            headers["X-CSRF-TOKEN"] = self.token
            if not isinstance(body, bytes):
                headers["Content-Type"] = "application/json"
                body = json.dumps(body).encode()
        with self.client.open(urllib.request.Request(self.url+path,body,headers),timeout=180) as response:
            raw = response.read()
            return json.loads(raw) if raw else None


def run_case(guide, kind, wording, citation):
    steps = [(LIST, dict(type="products", skus=["DEMO-003","DEMO-008"]))]
    quote = dict(type="products", skus=["DEMO-008"], quantity=2, totals={"DEMO-008":218}, citations=[citation])
    if kind in ("改选","取消"):
        steps.append((QUOTE,quote))
    if kind == "取消":
        steps.append((HANDOFF,dict(type="review_draft",draftSku="DEMO-008")))
    expected = {"改选":dict(type="products",skus=["DEMO-003"],quantity=2,totals={"DEMO-003":212},priceChecked=True,stockChecked=True),
                "联查":quote, "消歧":dict(type="clarify",asksPosition=True,draftSku=None),
                "取消":dict(type="knowledge",citations=[citation],draftSku=None)}[kind]
    steps.append((wording,expected))
    result = dict(scenario=kind,wording=wording,passed=False,turns=[])
    try:
        chat = guide.call("/api/conversations",{})
        for question, criteria in steps:
            criteria = dict(criteria, reviews=0)
            turn = dict(question=question,expected=criteria)
            result["turns"].append(turn)
            chat = guide.call("/api/conversations/"+chat["id"]+"/messages",
                dict(text=question,messageId=str(uuid.uuid4()),version=chat["version"]))
            reply = chat["turns"][-1]["reply"]
            actual = dict(type=reply["type"],skus=[p["sku"] for p in reply["products"]],
                quantity=reply.get("quantity"),totals=reply["totals"],
                citations=[[c[k] for k in ("title","version","section","excerpt")] for c in reply["citations"]],
                draftSku=(reply.get("reviewDraft") or {}).get("sku"),
                asksPosition="第几款" in reply["text"],priceChecked=any("价格核验" in s for s in reply["trace"]),
                stockChecked=any("库存" in s for s in reply["trace"]),
                reviews=len(chat["reviews"]))
            turn.update(actual=actual,text=reply["text"],trace=reply["trace"],
                mismatches=[k for k,v in criteria.items() if actual[k]!=v])
            if turn["mismatches"]:
                return result
        result["passed"] = True
    except (OSError, ValueError, KeyError, IndexError) as exc:
        result["error"] = str(exc)
    return result


def evaluate(url,password,model):
    ops = Session(url,"ops",password)
    ops.call("/api/ops/policies", dict(key="eval",title="模拟售后政策",scope="STORE-01",
        markdown=(ROOT/"fixtures/policies/returns-v2.md").read_text(encoding="utf-8")))
    ops.call("/api/ops/policies/eval/1/publish",{})
    guide = Session(url,"direct",password)
    # Chunk IDs are generated at publication; compare stable fixture fields.
    rows = []
    for kind,wording in CASES:
        rows.append(run_case(guide,kind,wording,EXPECTED))
        passed = sum(r["passed"] for r in rows)
        REPORT.write_text(json.dumps(dict(model=model,store="STORE-01",retrieval="LEXICAL",
            passed=passed,completed=len(rows),total=len(CASES),cases=rows),ensure_ascii=False,indent=2),encoding="utf-8")
        print(f"{kind} {len(rows)}/{len(CASES)}: {'PASS' if rows[-1]['passed'] else 'FAIL'}",flush=True)
    print(f"{passed}/{len(rows)}: {REPORT}")
    control = run_case(guide,"消歧","第一款还是第二款，我没想好。",EXPECTED)
    report = json.loads(REPORT.read_text(encoding="utf-8"))
    report["undecidedControl"] = control
    REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print("犹豫对照:", "PASS" if control["passed"] else "FAIL")
    return passed==len(rows) and control["passed"]


EXPECTED = ["模拟售后政策","1","模拟售后政策 / 第4条 已拆包","拆包商品需提供批次与照片，由售后专员复核后答复。"]


def evaluate_stockout(url, password, model):
    guide = Session(url, "direct", password)
    base = "要两包 L 码纸尿裤，"
    cases = [
        ("最好绒朵，其他牌子也行，总共220元", ["DEMO-003"]),
        ("只能要绒朵，总共220元", []),
        ("绒朵缺货可以换牌子，总共220元", ["DEMO-003"]),
        ("绒朵缺货可以换牌子，每包220元", ["DEMO-003", "DEMO-013", "DEMO-018"]),
        ("绒朵缺货，可以换牌子，总共220元", ["DEMO-003"]),
        ("绒朵缺货，只能换尺码，总共220元", []),
    ]
    rows = []
    for wording, expected in cases:
        question = base + wording
        chat = guide.call("/api/conversations", {})
        chat = guide.call("/api/conversations/"+chat["id"]+"/messages",
            dict(text=question, messageId=str(uuid.uuid4()), version=chat["version"]))
        reply = chat["turns"][-1]["reply"]
        actual = sorted(p["sku"] for p in reply["products"])
        totals = {sku: {"DEMO-003":212, "DEMO-013":264, "DEMO-018":290}[sku] for sku in expected}
        trace = reply["trace"]
        status = "products" if expected else ("clarify" if "尺码" in wording else "empty")
        checked = any("原款库存核验" in t for t in trace)
        searched = any("本店商品检索" in t for t in trace)
        passed = (actual == expected and totals == reply["totals"] and status == reply["type"]
                  and searched == bool(expected) and checked == (status != "clarify"))
        rows.append(dict(question=question, expectedSkus=expected, expectedTotals=totals,
            expectedType=status, reply=reply, passed=passed))
    path = REPORT.with_name("stockout-eval.json")
    path.write_text(json.dumps(dict(model=model, cases=rows), ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{sum(row['passed'] for row in rows)}/{len(rows)}: {path}")
    return all(row["passed"] for row in rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar",type=Path,help="use an already built application JAR")
    parser.add_argument("--stockout", action="store_true", help="run six paired shortage scenarios")
    args = parser.parse_args()
    if not os.environ.get("DASHSCOPE_API_KEY"):
        parser.error("Set DASHSCOPE_API_KEY for real Bailian calls.")
    jar = args.jar
    if jar is None:
        wrapper = ["cmd","/c","mvnw.cmd"] if os.name=="nt" else ["bash","mvnw"]
        subprocess.run(wrapper+["-DskipTests","package"],cwd=ROOT,check=True)
        jar, = (ROOT/"target").glob("baby-care-agent-*.jar")
    password = secrets.token_urlsafe(24)
    model = os.environ.get("BAILIAN_MODEL","qwen-plus")
    REPORT.parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory() as folder:
        fixtures = Path(folder)/"fixtures"
        shutil.copytree(ROOT/"fixtures",fixtures)
        if args.stockout:
            path = fixtures/"catalog-demo.json"
            data = json.loads(path.read_text(encoding="utf-8"))
            product = next(p for p in data["products"] if p["sku"] == "DEMO-008")
            offer = next(o for o in product["store_offers"] if o["store_id"] == "STORE-01")
            offer["available_packs"] = 1
            path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        promo = fixtures/"promotion-demo.json"
        rule = json.loads(promo.read_text(encoding="utf-8"))
        rule.update(startsOn="2000-01-01",endsOn="2099-12-31")
        promo.write_text(json.dumps(rule),encoding="utf-8")
        logfile = Path(folder)/"server.log"
        java = Path(os.environ["JAVA_HOME"])/"bin"/"java" if os.environ.get("JAVA_HOME") else "java"
        with logfile.open("wb") as log:
            server = subprocess.Popen([str(java),"-jar",str(jar.resolve()),"--server.port=0","--server.address=127.0.0.1",
                "--spring.profiles.active=default","--spring.datasource.url=jdbc:h2:mem:eval;MODE=PostgreSQL",
                "--babycare.retrieval.mode=LEXICAL","--babycare.fixtures="+str(fixtures),
                "--babycare.stock-source="+str(fixtures/"catalog-demo.json")],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,
                env=dict(os.environ,BABYCARE_DEMO_PASSWORD=password,BABYCARE_OPS_PASSWORD=password,BAILIAN_MODEL=model))
            try:
                for _ in range(120):
                    found = re.search(r"Tomcat started on port (\d+)",logfile.read_text(encoding="utf-8",errors="replace"))
                    if found:
                        evaluate_suite = evaluate_stockout if args.stockout else evaluate
                        return evaluate_suite("http://127.0.0.1:"+found[1],password,model)
                    if server.poll() is not None:
                        raise RuntimeError("Isolated evaluation server exited during startup")
                    time.sleep(0.5)
                raise TimeoutError("Evaluation server startup timeout")
            finally:
                server.terminate()
                try:
                    server.wait(10)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait()


if __name__=="__main__":
    raise SystemExit(0 if main() else 1)

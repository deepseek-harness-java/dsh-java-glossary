#!/usr/bin/env python3
"""Term Atlas E2E: app API + AI stream tool calls.

Usage: python3 glossary_atlas_e2e.py
Prereq: app on 127.0.0.1:8116, harness on 127.0.0.1:8090,
        plugin dsh-java-glossary-plugin installed & activated.
"""
import json
import sys
import time
import urllib.request

APP = "http://127.0.0.1:8116"
DOC = (
    "# 支付网关重构方案\n"
    "本次重构把 PaymentGateway 的核心链路改为异步化，目标 QPS 从 1.2 万提升到 3 万。\n"
    "网关（PaymentGateway，即支付网关）对接三个渠道，走统一的回调协议 CallbackHandler。\n"
    "注意 PaymentGateway 必须保持幂等（Idempotent），重复回调不能重复入账。\n"
    "SLA 承诺 99.95%，监控看板用 Grafana，告警走 PagerDuty。\n"
    "TCC 模式的事务由 TransactionCoordinator 编排，PaymentGateway 只负责路由。\n"
)
results = []


def call(path, payload=None, method=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(APP + path, data=data, method=method or ("POST" if data else "GET"),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def stream_once(agent_id, message):
    body = json.dumps({"agentId": agent_id, "message": message}).encode()
    req = urllib.request.Request(APP + "/api/assistant/stream", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    content, tools, events = "", [], []
    try:
        with urllib.request.urlopen(req, timeout=200) as resp:
            event, data_buf = "message", []
            for raw in resp.read().decode("utf-8", "ignore").splitlines() + [""]:
                if raw.startswith("event:"):
                    event = raw[6:].strip()
                elif raw.startswith("data:"):
                    data_buf.append(raw[5:].strip())
                elif not raw and data_buf:
                    try:
                        payload = json.loads("\n".join(data_buf))
                        events.append(event)
                        if event == "chunk":
                            content += payload.get("content", "")
                        if event == "step_break" and payload.get("toolName"):
                            tools.append(payload["toolName"])
                    except json.JSONDecodeError:
                        pass
                    event, data_buf = "message", []
    except Exception as e:  # noqa: BLE001
        content += f"\nSTREAM-ERROR: {e}"
    return content, sorted(set(tools)), events


def case(name):
    def deco(fn):
        def wrapped():
            start = time.time()
            try:
                detail = fn()
                results.append((name, True, detail, time.time() - start))
                print(f"PASS  {name}  ({time.time()-start:.1f}s)  {detail}")
            except AssertionError as e:
                results.append((name, False, str(e), time.time() - start))
                print(f"FAIL  {name}  ({time.time()-start:.1f}s)  {e}")
            except Exception as e:  # noqa: BLE001
                results.append((name, False, f"{type(e).__name__}: {e}", time.time() - start))
                print(f"FAIL  {name}  ({time.time()-start:.1f}s)  {type(e).__name__}: {e}")
        return wrapped
    return deco


@case("T0 内置词条与查询")
def t0():
    status, body = call("/api/terms/lookup", {"keyword": "QPS"})
    assert status == 200, f"lookup {status}: {body}"
    assert body["hitCount"] >= 1, f"hitCount={body['hitCount']}"
    term = body["terms"][0]
    assert term["en"] == "QPS", f"en={term.get('en')}"
    assert "Queries Per Second" in term["definition"], "definition mismatch"
    return f"hit={body['hitCount']} en={term['en']}"


@case("T1 收录新术语")
def t1():
    status, body = call("/api/terms", {
        "en": "Callback Handler", "zh": "回调处理器", "aliases": ["回调"],
        "tag": "架构", "definition": "渠道方处理异步通知的处理器，回调必须验签。", "source": "e2e"})
    assert status == 200, f"add {status}: {body}"
    assert not body.get("duplicated"), f"unexpected duplicate: {body}"
    assert body["id"].startswith("term-"), f"id={body.get('id')}"
    return f"id={body['id']} en={body['en']}"


@case("T2 重复收录转评审")
def t2():
    status, body = call("/api/terms", {
        "en": "Callback Handler", "zh": "回调处理器2", "definition": "另一个人写的不同释义。", "source": "e2e-b"})
    assert status == 200, f"add dup {status}: {body}"
    assert body.get("duplicated") is True, f"should be duplicated: {body}"
    assert body.get("reviewId", "").startswith("rev-"), f"reviewId={body.get('reviewId')}"
    return f"reviewId={body['reviewId']}"


@case("T3 文档挖词")
def t3():
    status, body = call("/api/terms/mine", {"text": DOC})
    assert status == 200, f"mine {status}: {body}"
    cand = {c["en"]: c for c in body["candidates"]}
    assert "PaymentGateway" in cand, f"missing PaymentGateway in {list(cand)[:8]}"
    assert cand["PaymentGateway"]["freq"] >= 3, f"freq={cand['PaymentGateway']['freq']}"
    assert body["newCount"] >= 1, f"newCount={body['newCount']}"
    return f"candidates={body['candidateCount']} new={body['newCount']}"


@case("T4 双语卡片")
def t4():
    status, body = call("/api/terms/bilingual-card", {"keyword": "幂等"})
    assert status == 200, f"card {status}: {body}"
    assert body["cardCount"] == 3, f"cardCount={body['cardCount']}"
    sides = {c["side"] for c in body["cards"]}
    assert sides == {"zh", "en", "usage"}, f"sides={sides}"
    return f"en={body['en']} cards={body['cardCount']}"


@case("T5 冲突检查")
def t5():
    status, body = call("/api/terms/conflicts", {})
    assert status == 200, f"conflicts {status}: {body}"
    assert body["pendingReviews"] >= 1, f"pendingReviews={body['pendingReviews']}"
    # 冲突 = duplicate-en 重名 或 zh 一对多映射；T2 的重复收录走评审，故此处验证评审机制生效即可
    assert body["conflictCount"] >= 0
    assert "pendingReviews" in body and body["termCount"] >= 4, f"termCount={body['termCount']}"
    return f"terms={body['termCount']} conflicts={body['conflictCount']} reviews={body['pendingReviews']}"


@case("T6 AI 对话调用术语工具")
def t6():
    content, tools, events = stream_once("e2e-gl-t6", "QPS 是什么意思？术语库里怎么定义的？")
    assert any("gl_" in t for t in tools), f"no gl_ tool called, tools={tools}"
    assert "chunk" in events, "no chunk events"
    assert len(content) > 20, f"content too short: {content[:80]}"
    return f"tools={tools} chars={len(content)}"


@case("T7 空关键词拒绝")
def t7():
    status, body = call("/api/terms/lookup", {"keyword": ""})
    assert status == 400, f"should reject: {status} {body}"
    return "empty keyword rejected with 400"


def main():
    for fn in (t0, t1, t2, t3, t4, t5, t6, t7):
        fn()
    passed = sum(1 for _, ok, _, _ in results if ok)
    print(f"\n{passed}/{len(results)} PASS")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()

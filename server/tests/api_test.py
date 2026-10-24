#!/usr/bin/env python3
"""跑得快 FakeLua 服务端的 HTTP 契约测试。

打的接口与旧版 Java 后端 GameHttpServer 一致, 所以这份用例同时是前端契约的回归:
状态 DTO 的键、新局发牌、非法出牌、AI 一步、提示、过牌、会话隔离, 以及一整局
跑到底的守恒检查 (48 张牌、摊牌、记牌器)。

用法:
    python3 server/tests/api_test.py                       # 自己拉起服务 (随机端口)
    python3 server/tests/api_test.py --server PATH         # 指定 paodekuai_server 可执行文件
    python3 server/tests/api_test.py --base-url http://... # 打已经跑着的服务
    python3 server/tests/api_test.py --jit 2               # 传给宿主的 jit 类型 (0=tcc 1=gcc 2=interp)

只依赖标准库。
"""
import argparse
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

DTO_KEYS = [
    "stage", "cardCounts", "humanHand", "activePlayer", "isGameOver",
    "winner", "isHumanWinner", "canPass", "playedStats", "playerActions",
    "aiThoughts", "logs",
]
# 没有值的字段在 JSON 里直接缺席 (前端的 if (!x) 判空等价于 null)
OPTIONAL_KEYS = ["message", "lastMove", "revealedHands"]
STOCK = {"3": 4, "4": 4, "5": 4, "6": 4, "7": 4, "8": 4, "9": 4, "10": 4,
         "J": 4, "Q": 4, "K": 4, "A": 3, "2": 1}

PASS = 0
FAIL = 0


class Failure(Exception):
    pass


def check(cond, msg):
    if not cond:
        raise Failure(msg)


class Client:
    def __init__(self, base, sid):
        self.base = base.rstrip("/")
        self.sid = sid

    def _call(self, method, path, body=None):
        url = self.base + path
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("X-Session-Id", self.sid)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            r = urllib.request.urlopen(req, timeout=60)
            return r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers

    def get_json(self, path):
        status, payload, _ = self._call("GET", path)
        check(status == 200, "GET %s -> %d" % (path, status))
        return json.loads(payload)

    def post_json(self, path, body=None):
        status, payload, _ = self._call("POST", path, body)
        return status, json.loads(payload) if payload else {}

    def get_raw(self, path):
        return self._call("GET", path)


def case(name, fn):
    global PASS, FAIL
    try:
        fn()
        PASS += 1
        print("  ok   %s" % name)
    except Exception as e:  # noqa: BLE001
        FAIL += 1
        print("  FAIL %s: %s" % (name, e))


def total_cards(d):
    return sum(d["cardCounts"]) + sum(d["playedStats"].values())


def new_game_for_human(client, tries=60):
    """首出座位随机, 重开直到轮到真人。"""
    for _ in range(tries):
        _, d = client.post_json("/api/game/new")
        if d["activePlayer"] == 0:
            return d
    raise Failure("60 次都没有轮到真人先出, 不太可能")


def new_game_for_ai(client, seat=None, tries=60):
    """首出座位随机, 重开直到轮到 AI (可指定座位)。"""
    for _ in range(tries):
        _, d = client.post_json("/api/game/new")
        if d["activePlayer"] != 0 and (seat is None or d["activePlayer"] == seat):
            return d
    raise Failure("60 次都没有轮到 AI 先出, 不太可能")


def test_static(client):
    status, payload, headers = client.get_raw("/")
    check(status == 200, "GET / -> %d" % status)
    check("text/html" in headers.get("Content-Type", ""), "/ 的 Content-Type 应为 html")
    check(b"human-hand" in payload, "index.html 里找不到前端挂载点")

    status, payload, headers = client.get_raw("/app.js")
    check(status == 200, "GET /app.js -> %d" % status)
    check("javascript" in headers.get("Content-Type", ""), "app.js 的 Content-Type 不对")

    status, _, _ = client.get_raw("/style.css")
    check(status == 200, "GET /style.css -> %d" % status)

    status, _, _ = client.get_raw("/definitely-missing.txt")
    check(status == 404, "不存在的静态文件应 404, 实际 %d" % status)

    # 路径穿越必须挡掉
    status, _, _ = client.get_raw("/../pom.xml")
    check(status in (404, 400), "路径穿越应被拒绝, 实际 %d" % status)


def test_preflight(client):
    req = urllib.request.Request(client.base + "/api/game/state", method="OPTIONS")
    r = urllib.request.urlopen(req, timeout=10)
    check(r.status in (200, 204), "OPTIONS -> %d" % r.status)


def test_new_dto(client):
    c = Client(client.base, "newdto")
    status, d = c.post_json("/api/game/new")
    check(status == 200, "POST /api/game/new -> %d" % status)
    for k in DTO_KEYS:
        check(k in d, "状态 DTO 缺少字段 %s" % k)
    for k in OPTIONAL_KEYS:
        check(k not in d or d[k] is not None, "可选字段 %s 缺席时应判空" % k)
    check(d["stage"] == "PLAYING", "新开局应直接在出牌阶段, 实际 %s" % d["stage"])
    check(len(d["humanHand"]) == 16, "真人应 16 张, 实际 %d" % len(d["humanHand"]))
    check(d["cardCounts"] == [16, 16, 16], "三家应各 16 张, 实际 %s" % d["cardCounts"])
    check(total_cards(d) == 48, "场上总牌数应为 48, 实际 %d" % total_cards(d))
    check(d["activePlayer"] in (0, 1, 2), "首出座位非法: %s" % d["activePlayer"])
    check(d["isGameOver"] is False and d["winner"] == -1, "新局不该终局")
    # 记牌器 13 个键全部预置为 0
    check(len(d["playedStats"]) == 13, "记牌器应 13 格, 实际 %d" % len(d["playedStats"]))
    for sym in STOCK:
        check(sym in d["playedStats"], "记牌器缺 %s" % sym)


def test_illegal_play(client):
    c = Client(client.base, "illegal")
    new_game_for_human(c)
    # 手里最多 4 张 3, 五张必非法
    status, d = c.post_json("/api/game/play", {"cards": ["3", "3", "3", "3", "3"]})
    check(status == 400, "非法出牌应 400, 实际 %d" % status)
    check(d.get("error"), "400 应带 error 说明")
    status, d = c.post_json("/api/game/play", {"cards": ["??"]})
    check(status == 400, "无法识别的点数应被拒, 实际 %d" % status)
    # 自由出牌 (首手) 时不能过牌
    status, d = c.post_json("/api/game/pass")
    check(status == 400, "首手应强制出牌, 实际 %d" % status)


def test_hint_is_legal(client):
    c = Client(client.base, "hint")
    new_game_for_human(c)
    h = c.get_json("/api/game/hint")
    check(h.get("hint"), "轮到真人必有提示, 实际 %s" % h)
    status, d = c.post_json("/api/game/play", {"cards": h["hint"]})
    check(status == 200, "提示的走法必须合法, 实际 %d" % status)
    check(d["activePlayer"] != 0, "出完应轮到 AI")


def test_ai_step(client):
    c = Client(client.base, "step")
    d = new_game_for_ai(c)
    check(d["activePlayer"] != 0, "应轮到 AI 先动")
    status, d = c.post_json("/api/game/ai-step")
    check(status == 200, "AI 一步应 200, 实际 %d" % status)
    check(not d["isGameOver"], "刚动一步不该就终局")
    thoughts = d["aiThoughts"]
    check(isinstance(thoughts, dict) and thoughts, "AI 出牌后应有思路数据")
    for key, t in thoughts.items():
        check(t["type"] == "play", "思路类型应为 play")
        check(t["timeMs"] >= 0, "推理耗时应非负")
        check(t["evals"], "候选列表不应为空")
        for ev in t["evals"]:
            check(0.0 <= ev["probability"] <= 100.0, "概率应落在百分数区间: %s" % ev)


def test_session_isolation(client):
    a = Client(client.base, "iso_a")
    b = Client(client.base, "iso_b")
    _, da = a.post_json("/api/game/new")
    _, db = b.post_json("/api/game/new")
    check(da["humanHand"] != db["humanHand"], "两个会话的发牌应当互相独立")
    da2 = a.get_json("/api/game/state")
    check(da2["humanHand"] == da["humanHand"], "会话 A 的状态不该被会话 B 的开局改写")


def play_full_game(client, max_steps=500):
    """真人一律照提示出牌, 轮到 AI 就 ai-step, 直到终局。"""
    steps = 0
    d = client.get_json("/api/game/state")
    while not d["isGameOver"] and steps < max_steps:
        if d["activePlayer"] == 0:
            h = client.get_json("/api/game/hint")
            if not h.get("hint"):
                check(h.get("isPass") and d.get("canPass"),
                      "轮到自己却没有可用走法: %s" % h)
                status, d = client.post_json("/api/game/pass")
            else:
                status, d = client.post_json("/api/game/play", {"cards": h["hint"]})
            check(status == 200, "按提示行动应合法, 实际 %d: %s" % (status, d.get("error")))
        else:
            status, d = client.post_json("/api/game/ai-step")
            check(status == 200, "AI 一步应 200, 实际 %d" % status)
        steps += 1
    check(d["isGameOver"], "%d 步仍未终局" % steps)
    return d, steps


def test_full_game(client):
    c = Client(client.base, "full")
    c.post_json("/api/game/new")
    d, steps = play_full_game(c)
    print("       一局 %d 步结束, 赢家 P%d" % (steps, d["winner"]))

    check(d["winner"] in (0, 1, 2), "赢家座位非法: %s" % d["winner"])
    check(d["isHumanWinner"] == (d["winner"] == 0), "真人胜负判定与赢家不一致")

    # 48 张牌守恒: 各家剩余 + 已打出
    check(total_cards(d) == 48, "终局总牌数应为 48, 实际 %d" % total_cards(d))
    check(d["cardCounts"][d["winner"]] == 0, "赢家手牌应已出完")

    revealed = d.get("revealedHands")
    check(revealed and len(revealed) == 3, "终局应摊牌")
    for i in range(3):
        check(len(revealed[i]) == d["cardCounts"][i], "%d 号位摊牌数与剩余张数不符" % i)
        check(len(revealed[i]) <= 16, "%d 号位摊牌数不可能超过 16 张" % i)
    # 记牌器不能超过每种点数的库存
    for sym, cap in STOCK.items():
        got = d["playedStats"].get(sym, 0)
        check(got <= cap, "%s 打出 %d 张, 超过库存 %d" % (sym, got, cap))
    check(sum(d["playedStats"].values()) == 48 - sum(d["cardCounts"]),
          "记牌器与剩余张数不吻合")


def test_status_no_leak(client):
    """反复重开同一会话不能把牌局对象堆在宿主里 (宿主没有 GC)。"""
    st = client.get_json("/api/status")
    check(st["status"] == "ok", "/api/status 应报 ok")
    check("policy=loaded" in st["backend"], "后端应报告权重已加载: %s" % st["backend"])
    before = st["games"]
    c = Client(client.base, "leak")
    for _ in range(6):
        c.post_json("/api/game/new")
    after = client.get_json("/api/status")["games"]
    check(after - before <= 1, "重开 6 局只该多 1 局: before=%d after=%d" % (before, after))


def find_free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def start_server(binary, lua_dir, port, jit):
    env = dict(os.environ)
    env["PAODEKUAI_PORT"] = str(port)
    cmd = [binary, lua_dir]
    if jit is not None:
        cmd.append("--jit_type=%d" % jit)
    log = open(os.devnull, "wb")
    proc = subprocess.Popen(cmd, cwd=os.path.dirname(lua_dir.rstrip("/")), env=env,
                            stdout=log, stderr=subprocess.STDOUT)
    base = "http://127.0.0.1:%d" % port
    deadline = time.time() + 60
    while time.time() < deadline:
        if proc.poll() is not None:
            raise SystemExit("服务启动失败, 退出码 %s" % proc.returncode)
        try:
            urllib.request.urlopen(base + "/", timeout=2).read()
            return proc, base
        except Exception:  # noqa: BLE001
            time.sleep(0.5)
    proc.terminate()
    raise SystemExit("服务在 60s 内没有就绪")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default=None, help="paodekuai_server 可执行文件路径")
    ap.add_argument("--base-url", default=None, help="测试已运行的服务")
    ap.add_argument("--jit", type=int, default=None, help="0=tcc 1=gcc 2=interp")
    args = ap.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    server_dir = os.path.dirname(here)
    proc = None
    if args.base_url:
        base = args.base_url
    else:
        binary = args.server or os.path.join(server_dir, "host", "build", "paodekuai_server")
        if not os.path.isfile(binary):
            raise SystemExit("找不到宿主可执行文件: %s (先 cmake --build host/build)" % binary)
        port = find_free_port()
        proc, base = start_server(binary, os.path.join(server_dir, "lua"), port, args.jit)
        print("已在 :%d 拉起服务" % port)

    client = Client(base, "test_main")
    try:
        print("静态资源:")
        case("页面与脚本可取, 路径穿越被挡", lambda: test_static(client))
        case("跨域预检", lambda: test_preflight(client))
        print("对局接口:")
        case("新局状态 DTO 契约", lambda: test_new_dto(client))
        case("非法出牌与首手过牌被拒", lambda: test_illegal_play(client))
        case("提示的走法一定合法", lambda: test_hint_is_legal(client))
        case("AI 一步与思路数据", lambda: test_ai_step(client))
        case("会话之间互不干扰", lambda: test_session_isolation(client))
        case("存活探针与牌局不泄漏", lambda: test_status_no_leak(client))
        print("整局:")
        case("跑到底: 48 张守恒与摊牌", lambda: test_full_game(client))
    finally:
        if proc is not None:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()

    print("\n通过 %d, 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

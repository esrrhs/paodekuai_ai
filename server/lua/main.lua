package "main"

-- 标准生命周期。宿主固定调用 package "main" 下的这三个函数, 每次调用都是独立的顶层
-- Call (帧 arena 在入口 Reset, 请求级临时内存随之回收):
--   init   : 启动一次, 建 HTTP 服务
--   update : 每帧一次, 泵 IO/定时器并分发 HTTP 请求
--   fini   : 退出前一次 (paodekuai.exit / update 返回非 0 都会跑到)
-- 切勿在 Lua 侧写 while true 把三者套进一个 Call (arena 永不 Reset 即内存泄露)。
--
-- 节拍直接决定 HTTP 延迟: 一个请求要跨三拍才走完 (收请求 -> 派发 -> 把响应泵出去),
-- 空闲时退避到 IDLE_TICK_INTERVAL_MS 不空转 CPU, 有请求来的那几拍再回到 1ms。
local BUSY_TICK_INTERVAL_MS = 1
local IDLE_TICK_INTERVAL_MS = 20
local BUSY_TICKS = 16
-- 每隔多少拍扫一次闲置会话 (会话回收要序列化整表, 不必每拍都做)
local SWEEP_TICKS = 512

-- 可变状态一律落 C++ 堆: 文件级 local 落在常量区不可写
local pace = container.map()

-- 自检: 引擎与权重可用 + 一条完整牌局跑得通 + 编码契约。
-- 用 PAODEKUAI_SMOKE=1 <宿主> lua --once 触发, init 返回非 0 即失败。
local function self_check()
    local ver = paodekuai.version()
    print("smoke version=", ver)
    if not string.contains(ver, "policy=loaded") then
        return 1
    end

    -- 1) 发牌: 三家各 16 张, 48 张守恒, 首出座位有效
    local gid = paodekuai.newgame(20261024)
    local st = json.decode(paodekuai.state(gid, 0))
    if st == nil or #PaoDef.CardsToSymbols(st.myHand) ~= 16 then
        print("smoke deal broken: ", tostring(st == nil and "nil" or st.myHand))
        return 1
    end
    if st.counts[1] + st.counts[2] + st.counts[3] ~= 48 then
        print("smoke deck not conserved")
        return 1
    end
    if st.active < 0 or st.active > 2 then
        print("smoke leader broken")
        return 1
    end

    -- 2) 三家全用策略网络跑完一局: 必须终局, 且不超步数上限
    local steps = 0
    while st.over ~= true and steps < 500 do
        local decided = PaoDef.ParseDecision(paodekuai.decide(gid))
        if decided == nil then
            print("smoke decide returned nothing at step=", tostring(steps))
            return 1
        end
        local cards = decided.chosen.type == 0 and "-" or decided.chosen.cards
        if paodekuai.apply(gid, cards) == "" then
            print("smoke chosen move rejected: ", cards)
            return 1
        end
        steps = steps + 1
        st = json.decode(paodekuai.state(gid, 0))
    end
    print("smoke selfplay steps=", tostring(steps), " over=", tostring(st.over), " winner=", tostring(st.winner))
    if st.over ~= true then
        return 1
    end
    if st.revealed == nil or #st.revealed ~= 3 then
        print("smoke hands not revealed at game over")
        return 1
    end

    -- 3) 会话必须显式释放, 否则宿主里的牌局一直占着内存
    if paodekuai.free(gid) ~= "1" then
        print("smoke free failed")
        return 1
    end
    if paodekuai.sessions() ~= 0 then
        print("smoke sessions not zero after free: ", tostring(paodekuai.sessions()))
        return 1
    end

    -- 4) 会话流程层: 建会话、状态 DTO、提示、AI 步进
    local dto = PaoGame.View("smoke_session", nil)
    if dto == nil or dto.stage ~= "PLAYING" or #dto.humanHand ~= 16 then
        print("smoke dto broken")
        return 1
    end
    if #dto.playerActions ~= 3 or #dto.cardCounts ~= 3 then
        print("smoke dto shape broken")
        return 1
    end
    -- 首出座位随机, 找到真人先手的会话再验提示 (最多试 50 次)
    local hint = nil
    local hint_tries = 0
    while hint_tries < 50 do
        PaoGame.New("smoke_session")
        local e = PaoGame.EngineOf("smoke_session")
        if e.active == 0 then
            hint = PaoGame.Hint("smoke_session")
            break
        end
        hint_tries = hint_tries + 1
    end
    if hint == nil or hint.hint == nil or #hint.hint == 0 then
        print("smoke hint empty")
        return 1
    end
    if PaoGame.Count() ~= 1 then
        print("smoke session count broken: ", tostring(PaoGame.Count()))
        return 1
    end

    -- 会话层的 AI 一步: 建一个 AI 先手的会话 (首出可能轮到任何座位, 最多试 50 次)
    local acted = nil
    local tries = 0
    while tries < 50 do
        PaoGame.New("smoke_ai")
        local e = PaoGame.EngineOf("smoke_ai")
        if e ~= nil and e.active ~= 0 then
            acted = PaoGame.AiStep("smoke_ai")
            break
        end
        tries = tries + 1
    end
    print("smoke ai step=", tostring(acted))
    if acted == nil or acted == "" then
        print("smoke ai step produced nothing")
        return 1
    end
    local dto2 = PaoGame.View("smoke_ai", nil)
    if type(dto2.aiThoughts) ~= "table" then
        print("smoke ai thoughts missing")
        return 1
    end

    -- 5) JSON 编码契约: 字符串键的表必须是对象 (前端的记牌器按键取值)
    local as_obj = json.encode({ ["10"] = 4, ["2"] = 1 })
    if not string.contains(as_obj, "\"10\"") then
        print("smoke json object encode broken: ", as_obj)
        return 1
    end

    -- 6) 牌面两套写法必须能原样往返: 前端提交的符号列表要能拼回引擎的紧凑写法
    local roundtrip = PaoDef.SymbolsToCards({ "10", "10", "J", "3", "2" })
    if roundtrip ~= "3TTJ2" then
        print("smoke cards roundtrip broken: ", roundtrip)
        return 1
    end
    if table.concat(PaoDef.CardsToSymbols(roundtrip), ",") ~= "3,10,10,J,2" then
        print("smoke cards decode broken")
        return 1
    end

    return 0
end

function init()
    local smoke = os.getenv("PAODEKUAI_SMOKE")
    if smoke ~= nil and #smoke > 0 then
        return self_check()
    end

    print("=================================================")
    print("  跑得快 AI 网页对战平台 (FakeLua + C++ 引擎版)")
    print("=================================================")
    print("推理后端: " .. paodekuai.version())

    local port = 8080
    local env_port = os.getenv("PAODEKUAI_PORT")
    if env_port ~= nil and #env_port > 0 then
        port = math.floor(tonumber(env_port) or 8080)
    end

    local cfg = {}
    cfg["ip"] = "0.0.0.0"
    cfg["port"] = port
    local srv = http.server(cfg)
    srv:dispatch("PaoHttp.OnRequest")
    print("HTTP 监听 :" .. tostring(port))
    print("浏览器打开 http://127.0.0.1:" .. tostring(port))
    print("-------------------------------------------------")
    return 0
end

-- 自适应节拍: 请求活跃期 1ms 保证响应及时, 空闲期退避到 20ms 省 CPU。
function update()
    runtime.tick()

    local seen = PaoHttp.ServedCount()
    if seen ~= pace:get("last_seen") then
        pace:set("last_seen", seen)
        pace:set("busy_left", BUSY_TICKS)
        pace:set("ticks", 0)
    end

    local ticks = (pace:get("ticks") or 0) + 1
    pace:set("ticks", ticks)
    if math.floor(ticks / SWEEP_TICKS) * SWEEP_TICKS == ticks then
        PaoGame.Sweep(os.time())
    end

    local ms = IDLE_TICK_INTERVAL_MS
    local busy_left = pace:get("busy_left")
    if busy_left ~= nil and busy_left > 0 then
        ms = BUSY_TICK_INTERVAL_MS
        pace:set("busy_left", busy_left - 1)
    end
    os.sleep(ms)
    return 0
end

function fini()
    print("PaoServer: shutdown")
    return 0
end

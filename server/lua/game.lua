package "PaoGame"

-- 会话流程编排。牌局本体 (手牌、合法动作、轮转、胜负) 全在宿主的 C++ 引擎里,
-- 这里只存「日志、每个座位最近一手、AI 思路」这类流程与展示状态。
--
-- 跑得快没有叫分: 发牌即开局, 首出座位由引擎按「4 张 3 里随机指定一张」给出。
--
-- fakelua 的文件级 table 是常量, container.map 又只能存标量: 会话整块 serialize
-- 成字符串挂在 map 上, 每次改动读一遍写一遍。

local store = container.map()

local MAX_LOGS = 100
-- 闲置超过这个秒数的会话连同宿主的牌局一起回收 (宿主没有 GC, 不回收就一直占着)
local EXPIRE_S = 3600
-- 真人固定坐 0 号位, 1/2 交给策略网络
local HUMAN = 0

local function load_session(sid)
    local bin = store:get(sid)
    if bin == nil then
        return nil
    end
    return serialize.decode(bin)
end

local function save_session(sid, s)
    s.seen = os.time()
    store:set(sid, serialize.encode(s))
end

local function add_log(s, text)
    local logs = s.logs
    logs[#logs + 1] = text
    if #logs > MAX_LOGS then
        local kept = {}
        local i = #logs - MAX_LOGS + 1
        while i <= #logs do
            kept[#kept + 1] = logs[i]
            i = i + 1
        end
        s.logs = kept
    end
end

local function seat_name(seat)
    if seat == HUMAN then
        return "玩家 P0 (真人)"
    end
    return "AI 玩家 P" .. tostring(seat)
end

local function blank(game_id)
    return {
        gameId = game_id,
        stage = "PLAYING",
        acts = { "", "", "" },
        thoughts = {},
        logs = {},
        seen = 0
    }
end

local function engine_of(s)
    return json.decode(paodekuai.state(s.gameId, HUMAN))
end

-- 桌面是否已清空 (自由出牌): 清台后各座位面前的展示归零
local function table_cleared(e)
    local m = PaoDef.ParseMove(e.toBeat)
    return m == nil or m.type == 0
end

-- 记下一手的展示, 并在终局时把阶段推进到 GAME_OVER
local function record_action(s, e, seat, move_text)
    if table_cleared(e) then
        s.acts = { "", "", "" }
    end
    s.acts[seat + 1] = move_text
    local after = engine_of(s)
    if after ~= nil and after.over == true then
        s.stage = "GAME_OVER"
    end
end

-- 暴露引擎原始状态 (自检与测试用)
function EngineOf(sid)
    local s = Ensure(sid)
    return engine_of(s)
end

function New(sid)
    -- 重开一局要先释放上一局的牌局对象: 宿主没有 GC, 不换掉就会一直堆
    local old = load_session(sid)
    if old ~= nil then
        paodekuai.free(old.gameId)
    end
    local gid = paodekuai.newgame(0)
    local s = blank(gid)
    local e = engine_of(s)
    add_log(s, "🎲 新局发牌完毕（各 16 张），由 " .. seat_name(e.active)
        .. " 先出，第一手必须包含 3！")
    save_session(sid, s)
    return s
end

function Ensure(sid)
    local s = load_session(sid)
    if s == nil then
        return New(sid)
    end
    s.seen = os.time()
    return s
end

function HumanPlay(sid, symbols)
    local s = Ensure(sid)
    local e = engine_of(s)
    if e.over == true then
        save_session(sid, s)
        return "游戏已结束，请重新开局"
    end
    if e.active ~= HUMAN then
        save_session(sid, s)
        return "当前不是你的回合，请等待 AI 行动"
    end

    local cards = PaoDef.SymbolsToCards(symbols)
    if cards == "" or #cards ~= #symbols then
        save_session(sid, s)
        return "无效的卡牌点数"
    end
    local applied = paodekuai.apply(s.gameId, cards)
    if applied == "" then
        save_session(sid, s)
        return "不符合跑得快规则或压不过上家牌！请重新选牌或使用 AI 提示。"
    end
    record_action(s, e, HUMAN, applied)
    add_log(s, "👉 " .. seat_name(HUMAN) .. " 出牌: "
        .. PaoDef.CardsToText(cards) .. " (" .. PaoDef.TypeName(PaoDef.MoveType(applied)) .. ")")
    save_session(sid, s)
    return nil
end

function HumanPass(sid)
    local s = Ensure(sid)
    local e = engine_of(s)
    if e.over == true then
        save_session(sid, s)
        return "游戏已结束，请重新开局"
    end
    if e.active ~= HUMAN then
        save_session(sid, s)
        return "当前不是你的回合"
    end
    if not PaoDef.HasPass(paodekuai.legal(s.gameId)) then
        save_session(sid, s)
        return "能管必管：有牌可压时不能过牌！"
    end
    local applied = paodekuai.apply(s.gameId, "-")
    if applied == "" then
        save_session(sid, s)
        return "当前不能过牌"
    end
    record_action(s, e, HUMAN, applied)
    add_log(s, "👉 " .. seat_name(HUMAN) .. " 选择了【不出/过牌】。")
    save_session(sid, s)
    return nil
end

-- 轮到 AI 行动一步: 出牌交给策略网络 (logits argmax)
function AiStep(sid)
    local s = Ensure(sid)
    local e = engine_of(s)
    if e.over == true then
        save_session(sid, s)
        return "游戏已结束"
    end
    if e.active == HUMAN then
        save_session(sid, s)
        return "轮到真人行动"
    end

    local t0 = os.clock()
    local decided = PaoDef.ParseDecision(paodekuai.decide(s.gameId))
    local cost = math.floor((os.clock() - t0) * 1000)
    if decided == nil then
        save_session(sid, s)
        return "AI 决策失败"
    end
    local chosen = decided.chosen
    local cards = chosen.type == 0 and "-" or chosen.cards
    local applied = paodekuai.apply(s.gameId, cards)
    if applied == "" then
        save_session(sid, s)
        return "AI 选出的动作不合法"
    end
    local seat = decided.seat
    record_action(s, e, seat, applied)
    s.thoughts["p" .. tostring(seat)] = {
        type = "play",
        move = PaoDef.CardsToText(chosen.cards),
        timeMs = cost,
        evals = decided.evals
    }
    if chosen.type == 0 then
        add_log(s, "🤖 " .. seat_name(seat) .. " 选择了【不出/过牌】")
    else
        add_log(s, "🤖 " .. seat_name(seat) .. " 出牌: "
            .. PaoDef.CardsToText(chosen.cards) .. " (" .. PaoDef.TypeName(chosen.type) .. ")")
    end
    save_session(sid, s)
    return PaoDef.CardsToText(chosen.cards)
end

-- AI 提示: 直接用策略网络给真人视角的最佳一手 (天然合法, 与 AI 出牌同源)
function Hint(sid)
    local s = load_session(sid)
    if s == nil or s.stage ~= "PLAYING" then
        return { hint = nil }
    end
    local e = engine_of(s)
    if e.over == true or e.active ~= HUMAN then
        return { hint = nil }
    end
    local decided = PaoDef.ParseDecision(paodekuai.decide(s.gameId))
    if decided == nil then
        return { hint = nil }
    end
    local chosen = decided.chosen
    if chosen.type == 0 then
        return { hint = nil, isPass = true, type = PaoDef.TypeName(0) }
    end
    return {
        hint = PaoDef.CardsToSymbols(chosen.cards),
        isPass = false,
        type = PaoDef.TypeName(chosen.type)
    }
end

-- 前端要的完整状态 DTO, 字段与旧版 Java 后端的 buildStateDto 一一对应
function View(sid, message)
    local s = Ensure(sid)
    local e = engine_of(s)
    if e == nil then
        return nil
    end
    if e.over == true then
        s.stage = "GAME_OVER"
        save_session(sid, s)
    end

    local dto = {}
    dto.message = message
    dto.stage = s.stage
    dto.cardCounts = e.counts
    dto.humanHand = PaoDef.CardsToSymbols(e.myHand)

    dto.activePlayer = e.active
    dto.isGameOver = e.over == true
    dto.winner = e.winner
    dto.isHumanWinner = e.winner == HUMAN
    if e.over == true and e.revealed ~= nil then
        local hands = {}
        local k = 1
        while k <= #e.revealed do
            hands[k] = PaoDef.CardsToSymbols(e.revealed[k])
            k = k + 1
        end
        dto.revealedHands = hands
    else
        dto.revealedHands = nil
    end

    local tb = PaoDef.ParseMove(e.toBeat)
    if tb == nil or tb.type == 0 then
        dto.lastMove = nil
    else
        dto.lastMove = {
            playerId = e.lead,
            type = PaoDef.TypeName(tb.type),
            cards = PaoDef.CardsToSymbols(tb.cards)
        }
    end

    dto.canPass = PaoDef.HasPass(paodekuai.legal(s.gameId))
    dto.playedStats = PaoDef.PlayedStats(e.history)

    local acts = {}
    local a = 1
    while a <= 3 do
        local m = PaoDef.ParseMove(s.acts[a])
        if m == nil then
            acts[a] = {}
        else
            acts[a] = {
                playerId = a - 1,
                isPass = m.type == 0,
                type = PaoDef.TypeName(m.type),
                cards = PaoDef.CardsToSymbols(m.cards)
            }
        end
        a = a + 1
    end
    dto.playerActions = acts

    dto.aiThoughts = s.thoughts
    dto.logs = s.logs
    return dto
end

-- 回收闲置会话: 会话本体是序列化字符串, 只按 seen 比较
function Sweep(now)
    local keys = store:keys()
    local i = 1
    while i <= #keys do
        local sid = keys[i]
        local s = load_session(sid)
        if s ~= nil and now - (s.seen or 0) > EXPIRE_S then
            paodekuai.free(s.gameId)
            store:erase(sid)
        end
        i = i + 1
    end
end

function Count()
    return #store:keys()
end

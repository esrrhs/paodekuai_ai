package "PaoDef"

-- 牌面与牌型的两套写法只在这里转换:
--   引擎侧 = 训练侧的紧凑写法, 一个字符一张牌 (3456789 T J Q K A 2), 过牌写 "-"
--   前端   = 符号数组 ("3".."9" "10" "J" "Q" "K" "A" "2")
-- 其余各层只认其中一种, 转换全部收在这个文件里。

local CHARS = { "3", "4", "5", "6", "7", "8", "9", "T", "J", "Q", "K", "A", "2" }
local SYMBOLS = { "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2" }

-- 引擎 MoveType 序号 0..10 -> 中文名, 与训练侧 pdk/moves.h 的 MoveType 对齐
local TYPE_NAMES = {
    "过牌", "单张", "对子", "三张", "炸弹", "三带二", "顺子", "连对",
    "三顺", "飞机带单", "飞机带对"
}

function RankCount()
    return 13
end

function TypeName(t)
    if t == nil or t < 0 or t > 10 then
        return "?"
    end
    return TYPE_NAMES[t + 1]
end

function SymbolOfChar(ch)
    local i = 1
    while i <= 13 do
        if CHARS[i] == ch then
            return SYMBOLS[i]
        end
        i = i + 1
    end
    return nil
end

-- "33T" -> {"3","3","10"}; 认不出的字符丢掉
function CardsToSymbols(cards)
    local out = {}
    if cards == nil then
        return out
    end
    local i = 1
    while i <= string.len(cards) do
        local sym = SymbolOfChar(string.sub(cards, i, i))
        if sym ~= nil then
            out[#out + 1] = sym
        end
        i = i + 1
    end
    return out
end

-- {"3","3","10"} -> "33T"; 按引擎的点数序回拼, 与 CardSet::str 逐字符一致。
-- 认不出的符号直接丢掉: 脏输入必须在这一层挡掉, 不能让宿主抛错把服务带崩。
function SymbolsToCards(list)
    local idxs = {}
    local i = 1
    while list ~= nil and i <= #list do
        local sym = string.upper(string.trim(tostring(list[i])))
        local j = 1
        while j <= 13 do
            if SYMBOLS[j] == sym then
                idxs[#idxs + 1] = j
                break
            end
            j = j + 1
        end
        i = i + 1
    end
    table.sort(idxs)
    local out = ""
    local k = 1
    while k <= #idxs do
        out = out .. CHARS[idxs[k]]
        k = k + 1
    end
    return out
end

function CardsToText(cards)
    local list = CardsToSymbols(cards)
    if #list == 0 then
        return "不出"
    end
    return table.concat(list, ",")
end

-- 一手牌的线格式 "type:rank:len:cards" -> {type=, rank=, len=, cards=}
function ParseMove(text)
    if text == nil or text == "" then
        return nil
    end
    local p = string.split(text, ":")
    if #p < 4 then
        return nil
    end
    return {
        type = math.floor(tonumber(p[1]) or -1),
        rank = math.floor(tonumber(p[2]) or 0),
        len = math.floor(tonumber(p[3]) or 1),
        cards = p[4]
    }
end

-- 宿主的 decide 回包 "chosen|seat|p:cards,p:cards,..."
function ParseDecision(text)
    if text == nil or text == "" then
        return nil
    end
    local parts = string.split(text, "|")
    if #parts < 3 then
        return nil
    end
    local chosen = ParseMove(parts[1])
    if chosen == nil then
        return nil
    end
    local ret = { chosen = chosen, seat = math.floor(tonumber(parts[2]) or 0), evals = {} }
    local i = 3
    while i <= #parts do
        local ev = string.split(parts[i], ":")
        if #ev >= 2 then
            local p = tonumber(ev[1])
            if p ~= nil then
                -- 前端显示的是百分数, 保留一位小数
                ret.evals[#ret.evals + 1] = {
                    cards = CardsToText(ev[2]),
                    probability = math.floor(p * 1000 + 0.5) / 10
                }
            end
        end
        i = i + 1
    end
    return ret
end

-- 宿主 state 的历史项 "seat:cards"
function ParseHistory(text)
    local p = string.split(text, ":")
    if #p < 2 then
        return nil
    end
    return {
        seat = math.floor(tonumber(p[1]) or -1),
        cards = p[2]
    }
end

-- 宿主 apply 的回包 "type:rank:len:cards", 记日志时要拿牌型序号
function MoveType(text)
    local m = ParseMove(text)
    if m == nil then
        return -1
    end
    return m.type
end

-- paodekuai.legal 的 "type:rank:len:cards|..." -> 一手牌列表
function ParseMoves(text)
    local out = {}
    if text == nil or text == "" then
        return out
    end
    local list = string.split(text, "|")
    local i = 1
    while i <= #list do
        local m = ParseMove(list[i])
        if m ~= nil then
            out[#out + 1] = m
        end
        i = i + 1
    end
    return out
end

function HasPass(text)
    local moves = ParseMoves(text)
    local i = 1
    while i <= #moves do
        if moves[i].type == 0 then
            return true
        end
        i = i + 1
    end
    return false
end

function Symbols()
    return SYMBOLS
end

-- 记牌器: 13 个点数各占一格, 值 = 已经打出的张数。键全部预置成 0, 空场时也是 13 格。
function PlayedStats(history)
    local stats = {}
    local i = 1
    while i <= 13 do
        stats[SYMBOLS[i]] = 0
        i = i + 1
    end
    local h = 1
    while history ~= nil and h <= #history do
        local m = ParseHistory(history[h])
        if m ~= nil and m.cards ~= "-" and m.cards ~= "" then
            local cs = CardsToSymbols(m.cards)
            local c = 1
            while c <= #cs do
                stats[cs[c]] = (stats[cs[c]] or 0) + 1
                c = c + 1
            end
        end
        h = h + 1
    end
    return stats
end

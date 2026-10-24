package "PaoHttp"

-- HTTP 路由与静态资源。所有牌局动作都转给 PaoGame, 这里只管收发与错误码。
--
-- 接口与旧版 Java 后端 GameHttpServer 一致 (前端 static/ 就是那一版的):
--   GET  /api/game/state            当前视角的完整状态
--   POST /api/game/new              新开一局
--   POST /api/game/play  {"cards":["3","3","4"]}
--   POST /api/game/pass
--   POST /api/game/ai-step          让当前 AI 行动一步
--   GET  /api/game/hint             AI 给真人的一步提示
-- 会话用 X-Session-Id 头隔离, 同一浏览器固定一个 id。

local static_root = "static"

-- 已派发的请求数。文件级 local 落在常量区不可写, 计数放 container.map(C++ 堆)。
local served = container.map()

-- 会话表在 PaoGame 那边, 这里只负责认出请求属于哪个会话
local SESSION_MAX_LEN = 64

function ServedCount()
    return served:get("n") or 0
end

local function cors_headers()
    local h = {}
    h["Access-Control-Allow-Origin"] = "*"
    h["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
    h["Access-Control-Allow-Headers"] = "Content-Type, X-Session-Id"
    h["Content-Type"] = "application/json; charset=UTF-8"
    return h
end

local function respond(status, body, extra)
    local resp = {}
    resp["status"] = status
    local h = cors_headers()
    if extra ~= nil then
        local k = 1
        while k <= #extra do
            h[extra[k][1]] = extra[k][2]
            k = k + 1
        end
    end
    resp["headers"] = h
    resp["body"] = body
    return resp
end

local function json_ok(body_tbl, sid)
    return respond(200, json.encode(body_tbl), { { "X-Session-Id", sid } })
end

-- 业务错误也带状态回来, 前端把 error 弹成 toast (与旧版 400 + DTO 一致)
local function json_err(status, tbl, sid)
    return respond(status, json.encode(tbl), { { "X-Session-Id", sid } })
end

local function json_raw(status, text)
    return respond(status, text, nil)
end

local function parse_body(body)
    if body == nil or body == "" then
        return {}
    end
    local ok, t = pcall(function()
        return json.decode(body)
    end)
    if ok and type(t) == "table" then
        return t
    end
    return {}
end

-- 只接受 [A-Za-z0-9_-] 的会话号且限长: 会话表是宿主机上的常驻 map, 不能被
-- 任一路人用超长或重复的键撑爆。
local function valid_session(sid)
    if sid == nil then
        return false
    end
    if #sid < 1 or #sid > SESSION_MAX_LEN then
        return false
    end
    return string.match(sid, "^[A-Za-z0-9_%-]+$") ~= nil
end

local function session_id(req)
    local sid = nil
    if req.headers ~= nil then
        sid = req.headers["X-Session-Id"]
        if sid == nil then
            sid = req.headers["x-session-id"]
        end
    end
    if not valid_session(sid) then
        -- 客户端没带合法会话号时现造一个; 两次随机取值, 避免同一秒新建的两个浏览器撞上同一局
        sid = "sess" .. tostring(os.time()) .. tostring(math.random(100000, 999999))
            .. tostring(math.random(100000, 999999))
    end
    return sid
end

local function mime(path)
    if string.ends_with(path, ".html") then return "text/html; charset=UTF-8" end
    if string.ends_with(path, ".css") then return "text/css; charset=UTF-8" end
    if string.ends_with(path, ".js") then return "application/javascript; charset=UTF-8" end
    if string.ends_with(path, ".svg") then return "image/svg+xml" end
    if string.ends_with(path, ".png") then return "image/png" end
    if string.ends_with(path, ".ico") then return "image/x-icon" end
    if string.ends_with(path, ".json") then return "application/json; charset=UTF-8" end
    return "application/octet-stream"
end

local function serve_static(path)
    if path == "/" or path == "" then
        path = "/index.html"
    end
    -- 只按字面拼路径, 带 .. 的一律拒绝 (fakelua 没有 realpath)
    if string.find(path, "..", 1, true) ~= nil then
        return json_raw(404, "404 Not Found")
    end
    local f = io.open(static_root .. path, "rb")
    if f == nil then
        return json_raw(404, "404 Not Found")
    end
    local body = f:read("*a")
    f:close()
    local resp = {}
    resp["status"] = 200
    local h = {}
    h["Content-Type"] = mime(path)
    h["Access-Control-Allow-Origin"] = "*"
    resp["headers"] = h
    resp["body"] = body
    return resp
end

-- DTO 里的 error 非空时按 400 回, 前端据此弹 toast 而不是当成正常状态
local function reply(dto, sid)
    if dto == nil then
        return json_err(500, { error = "服务内部状态异常" }, sid)
    end
    if dto.error ~= nil then
        return json_err(400, dto, sid)
    end
    return json_ok(dto, sid)
end

local function with_state(sid, message)
    return PaoGame.View(sid, message)
end

function OnRequest(typ, connid, req)
    if typ ~= "request" then
        return nil
    end
    served:set("n", ServedCount() + 1)

    local method = req.method
    local path = req.path
    if method == "OPTIONS" then
        return respond(204, "", nil)
    end

    -- 存活探针: 会话数与宿主牌局数应当同量级 (牌局只在会话重开与闲置回收时释放)
    if path == "/api/status" then
        return json_ok({ status = "ok", backend = paodekuai.version(),
                         sessions = PaoGame.Count(), games = paodekuai.sessions() }, "")
    end

    if string.starts_with(path, "/api/game") then
        local sid = session_id(req)
        local body = parse_body(req.body)

        if path == "/api/game/state" then
            return reply(with_state(sid, nil), sid)
        end

        if path == "/api/game/new" then
            if method ~= "POST" then
                return json_raw(405, "405 Method Not Allowed")
            end
            PaoGame.New(sid)
            return reply(with_state(sid, "新对局已发牌！"), sid)
        end

        if path == "/api/game/play" then
            if method ~= "POST" then
                return json_raw(405, "405 Method Not Allowed")
            end
            local err = PaoGame.HumanPlay(sid, body["cards"])
            if err ~= nil then
                local dto = with_state(sid, nil)
                dto.error = err
                return reply(dto, sid)
            end
            return reply(with_state(sid, "出牌成功！"), sid)
        end

        if path == "/api/game/pass" then
            if method ~= "POST" then
                return json_raw(405, "405 Method Not Allowed")
            end
            local err = PaoGame.HumanPass(sid)
            if err ~= nil then
                local dto = with_state(sid, nil)
                dto.error = err
                return reply(dto, sid)
            end
            return reply(with_state(sid, "已过牌"), sid)
        end

        if path == "/api/game/ai-step" then
            if method ~= "POST" then
                return json_raw(405, "405 Method Not Allowed")
            end
            local action = PaoGame.AiStep(sid)
            return reply(with_state(sid, "AI 决策: " .. tostring(action)), sid)
        end

        if path == "/api/game/hint" then
            return json_ok(PaoGame.Hint(sid), sid)
        end

        return json_raw(404, "404 Not Found")
    end

    return serve_static(path)
end

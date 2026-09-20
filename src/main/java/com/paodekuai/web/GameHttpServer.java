package com.paodekuai.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paodekuai.ai.PimcAiPlayer;
import com.paodekuai.game.GameState;
import com.paodekuai.model.Move;
import com.paodekuai.model.Rank;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 嵌入式 HTTP 服务器 (支持多 Session 并发隔离、REST API 与 Web 静态页面)
 */
public class GameHttpServer {
    private final int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    // 会话容器：支持多个玩家/浏览器同时独立游戏
    private static class SessionContainer {
        final GameSession session;
        volatile long lastAccessTime;

        SessionContainer(GameSession session) {
            this.session = session;
            this.lastAccessTime = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<String, SessionContainer> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sessionCleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Session-Cleaner");
        t.setDaemon(true);
        return t;
    });

    public GameHttpServer(int port) {
        this.port = port;
        // 每 10 分钟自动清理超过 1 小时未活动的闲置 Session
        this.sessionCleaner.scheduleAtFixedRate(this::cleanExpiredSessions, 10, 10, TimeUnit.MINUTES);
    }

    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        long expireTime = 3600_000; // 1 小时
        sessions.entrySet().removeIf(entry -> (now - entry.getValue().lastAccessTime) > expireTime);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // API 路由
        server.createContext("/api/game/state", this::handleState);
        server.createContext("/api/game/new", this::handleNewGame);
        server.createContext("/api/game/play", this::handlePlay);
        server.createContext("/api/game/pass", this::handlePass);
        server.createContext("/api/game/ai-step", this::handleAiStep);
        server.createContext("/api/game/hint", this::handleHint);

        // 静态资源路由
        server.createContext("/", this::handleStatic);

        // 使用轻量线程池处理并发请求
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("===============================================================");
        System.out.println(" 🌐 跑得快 AI 网页客户端已启动！(支持多用户并发对局)");
        System.out.printf(" 🎮 请在浏览器中打开: http://localhost:%d\n", port);
        System.out.println("===============================================================");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        sessionCleaner.shutdown();
    }

    private String getOrInitSessionId(HttpExchange exchange) {
        // 优先从 Header 获取
        String sessionId = exchange.getRequestHeaders().getFirst("X-Session-Id");
        if (sessionId == null || sessionId.isBlank()) {
            // 尝试从 Query Param 获取
            URI uri = exchange.getRequestURI();
            String query = uri.getQuery();
            if (query != null && query.contains("sessionId=")) {
                for (String param : query.split("&")) {
                    if (param.startsWith("sessionId=")) {
                        sessionId = param.substring("sessionId=".length());
                        break;
                    }
                }
            }
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "");
        }
        exchange.getResponseHeaders().set("X-Session-Id", sessionId);
        return sessionId;
    }

    private GameSession getSession(HttpExchange exchange) {
        String sessionId = getOrInitSessionId(exchange);
        SessionContainer container = sessions.computeIfAbsent(sessionId, id -> new SessionContainer(new GameSession()));
        container.touch();
        return container.session;
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.isBlank()) {
            path = "/index.html";
        }

        String resourcePath = "static" + path;
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            sendResponse(exchange, 404, "Not Found", "text/plain");
            return;
        }

        byte[] bytes = is.readAllBytes();
        String contentType = "text/html; charset=utf-8";
        if (path.endsWith(".css")) {
            contentType = "text/css; charset=utf-8";
        } else if (path.endsWith(".js")) {
            contentType = "application/javascript; charset=utf-8";
        }
        sendResponse(exchange, 200, bytes, contentType);
    }

    private void handleState(HttpExchange exchange) throws IOException {
        GameSession session = getSession(exchange);
        sendJson(exchange, 200, buildStateDto(session, null));
    }

    private void handleNewGame(HttpExchange exchange) throws IOException {
        GameSession session = getSession(exchange);
        session.newGame();
        sendJson(exchange, 200, buildStateDto(session, "新对局已创建！"));
    }

    private void handlePlay(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }

        GameSession session = getSession(exchange);
        InputStream is = exchange.getRequestBody();
        Map<?, ?> body = mapper.readValue(is, Map.class);
        List<?> cardsRaw = (List<?>) body.get("cards");
        List<String> cards = new ArrayList<>();
        if (cardsRaw != null) {
            for (Object o : cardsRaw) {
                cards.add(o.toString());
            }
        }

        String error = session.humanPlay(cards);
        if (error != null) {
            Map<String, Object> resp = buildStateDto(session, null);
            resp.put("error", error);
            sendJson(exchange, 400, resp);
        } else {
            sendJson(exchange, 200, buildStateDto(session, "出牌成功！"));
        }
    }

    private void handlePass(HttpExchange exchange) throws IOException {
        GameSession session = getSession(exchange);
        String error = session.humanPass();
        if (error != null) {
            Map<String, Object> resp = buildStateDto(session, null);
            resp.put("error", error);
            sendJson(exchange, 400, resp);
        } else {
            sendJson(exchange, 200, buildStateDto(session, "已过牌"));
        }
    }

    private void handleAiStep(HttpExchange exchange) throws IOException {
        GameSession session = getSession(exchange);
        String moveStr = session.aiStep();
        sendJson(exchange, 200, buildStateDto(session, "AI 思考完成: " + moveStr));
    }

    private void handleHint(HttpExchange exchange) throws IOException {
        GameSession session = getSession(exchange);
        Move hint = session.getHumanHint();
        Map<String, Object> resp = new HashMap<>();
        if (hint == null) {
            resp.put("hint", null);
        } else {
            List<String> cards = new ArrayList<>();
            for (Rank r : hint.getCards()) {
                cards.add(r.getSymbol());
            }
            resp.put("hint", cards);
            resp.put("isPass", hint.isPass());
            resp.put("type", hint.getType().getDescription());
        }
        sendJson(exchange, 200, resp);
    }

    private Map<String, Object> buildStateDto(GameSession session, String message) {
        GameState state = session.getGameState();
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("message", message);
        dto.put("activePlayer", state.getActivePlayerIndex());
        dto.put("isGameOver", state.isGameOver());
        dto.put("winner", state.getWinnerId());

        // 玩家 0 (真人) 的手牌
        List<String> humanHand = new ArrayList<>();
        for (Rank r : state.getPlayer(0).getHand().getCards()) {
            humanHand.add(r.getSymbol());
        }
        dto.put("humanHand", humanHand);

        // 各家剩余牌数
        int[] counts = new int[3];
        for (int i = 0; i < 3; i++) {
            counts[i] = state.getPlayer(i).getCardCount();
        }
        dto.put("cardCounts", counts);

        // 桌面最新出牌 (当前轮次要被压制的目标)
        Move lastMove = state.getLastMove();
        if (lastMove != null && !lastMove.isPass()) {
            Map<String, Object> lastMoveMap = new HashMap<>();
            lastMoveMap.put("playerId", state.getLastMovePlayerId());
            lastMoveMap.put("type", lastMove.getType().getDescription());
            List<String> cards = new ArrayList<>();
            for (Rank r : lastMove.getCards()) {
                cards.add(r.getSymbol());
            }
            lastMoveMap.put("cards", cards);
            dto.put("lastMove", lastMoveMap);
        } else {
            dto.put("lastMove", null);
        }

        // 3位玩家各自面前展示的最新动作 (出牌或不出/PASS)
        List<Map<String, Object>> playerActions = new ArrayList<>();
        Move[] actions = session.getPlayerLastActions();
        for (int i = 0; i < 3; i++) {
            Move m = actions[i];
            if (m == null) {
                playerActions.add(null);
            } else {
                Map<String, Object> act = new HashMap<>();
                act.put("playerId", i);
                act.put("isPass", m.isPass());
                act.put("type", m.getType().getDescription());
                List<String> cards = new ArrayList<>();
                for (Rank r : m.getCards()) {
                    cards.add(r.getSymbol());
                }
                act.put("cards", cards);
                playerActions.add(act);
            }
        }
        dto.put("playerActions", playerActions);

        // 真人是否可以不出
        List<Move> legalMoves = state.getLegalMoves();
        boolean canPass = legalMoves.stream().anyMatch(Move::isPass);
        dto.put("canPass", canPass);

        // 记牌器 (统计已打出的各点数数量)
        Map<String, Integer> playedStats = new LinkedHashMap<>();
        for (int v = 3; v <= 15; v++) {
            playedStats.put(Rank.fromValue(v).getSymbol(), 0);
        }
        for (Move m : state.getMoveHistory()) {
            if (!m.isPass()) {
                for (Rank r : m.getCards()) {
                    playedStats.put(r.getSymbol(), playedStats.get(r.getSymbol()) + 1);
                }
            }
        }
        dto.put("playedStats", playedStats);

        // AI 思考推演信息 (最近一次 P1 / P2 的决策分析)
        Map<String, Object> aiThoughtsDto = new HashMap<>();
        for (Map.Entry<Integer, PimcAiPlayer.DecisionResult> e : session.getLastAiThoughts().entrySet()) {
            PimcAiPlayer.DecisionResult res = e.getValue();
            List<Map<String, Object>> evals = new ArrayList<>();
            int limit = Math.min(4, res.getEvaluations().size());
            for (int i = 0; i < limit; i++) {
                PimcAiPlayer.MoveEvaluation me = res.getEvaluations().get(i);
                evals.add(Map.of(
                        "cards", me.getMove().toCardString(),
                        "visits", me.getTotalVisits(),
                        "winRate", Math.round(me.getAverageWinRate() * 1000.0) / 10.0
                ));
            }
            aiThoughtsDto.put("p" + e.getKey(), Map.of(
                    "move", res.getSelectedMove().toCardString(),
                    "timeMs", res.getDurationMillis(),
                    "evals", evals
            ));
        }
        dto.put("aiThoughts", aiThoughtsDto);

        // 事件日志
        dto.put("logs", session.getEventLogs());

        return dto;
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object data) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(data);
        sendResponse(exchange, statusCode, bytes, "application/json; charset=utf-8");
    }

    private void sendResponse(HttpExchange exchange, int statusCode, Object body, String contentType) throws IOException {
        byte[] bytes;
        if (body instanceof byte[] b) {
            bytes = b;
        } else {
            bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

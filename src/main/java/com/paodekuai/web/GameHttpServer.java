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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 嵌入式 HTTP 服务器 (提供 REST API 与 Web 静态页面)
 */
public class GameHttpServer {
    private final int port;
    private final GameSession session;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    public GameHttpServer(int port) {
        this.port = port;
        this.session = new GameSession();
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

        server.setExecutor(null);
        server.start();
        System.out.println("===============================================================");
        System.out.println(" 🌐 跑得快 AI 网页客户端已启动！");
        System.out.printf(" 🎮 请在浏览器中打开: http://localhost:%d\n", port);
        System.out.println("===============================================================");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
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
        sendJson(exchange, 200, buildStateDto(null));
    }

    private void handleNewGame(HttpExchange exchange) throws IOException {
        session.newGame();
        sendJson(exchange, 200, buildStateDto("新对局已创建！"));
    }

    private void handlePlay(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }

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
            Map<String, Object> resp = buildStateDto(null);
            resp.put("error", error);
            sendJson(exchange, 400, resp);
        } else {
            sendJson(exchange, 200, buildStateDto("出牌成功！"));
        }
    }

    private void handlePass(HttpExchange exchange) throws IOException {
        String error = session.humanPass();
        if (error != null) {
            Map<String, Object> resp = buildStateDto(null);
            resp.put("error", error);
            sendJson(exchange, 400, resp);
        } else {
            sendJson(exchange, 200, buildStateDto("已过牌"));
        }
    }

    private void handleAiStep(HttpExchange exchange) throws IOException {
        String moveStr = session.aiStep();
        sendJson(exchange, 200, buildStateDto("AI 思考完成: " + moveStr));
    }

    private void handleHint(HttpExchange exchange) throws IOException {
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

    private Map<String, Object> buildStateDto(String message) {
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

        // 桌面最新出牌
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

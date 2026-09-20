package com.paodekuai.web;

import com.paodekuai.ai.PimcAiPlayer;
import com.paodekuai.game.GameState;
import com.paodekuai.game.PublicView;
import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import com.paodekuai.model.Rank;
import com.paodekuai.rules.Deck;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 网页游戏会话控制器 (支持 1 真人 + 2 AI，多会话独立隔离)
 */
public class GameSession {
    private GameState gameState;
    private final PimcAiPlayer aiPlayer1;
    private final PimcAiPlayer aiPlayer2;
    private final PimcAiPlayer hintAi;
    private final Map<Integer, PimcAiPlayer.DecisionResult> lastAiThoughts = new HashMap<>();
    private final List<String> eventLogs = new ArrayList<>();
    // 记录每位玩家在当前轮次面前展示的最新动作 (出牌或过牌)
    private final Move[] playerLastActions = new Move[3];
    private final Random random = new Random();

    public GameSession() {
        this.aiPlayer1 = new PimcAiPlayer(200, 1500, random);
        this.aiPlayer2 = new PimcAiPlayer(200, 1500, random);
        this.hintAi = new PimcAiPlayer(40, 400, random);
        newGame();
    }

    public synchronized void newGame() {
        List<Hand> hands = Deck.deal(random);
        // 玩家 0 为人类玩家，玩家 1 和 2 为 AI
        this.gameState = new GameState(hands, 0, true);
        this.lastAiThoughts.clear();
        this.eventLogs.clear();
        Arrays.fill(this.playerLastActions, null);
        addLog("🎲 牌局开始！每位玩家分得 16 张牌，由玩家 P0 (真人) 先手出牌。");
    }

    public synchronized GameState getGameState() {
        return gameState;
    }

    public synchronized List<String> getEventLogs() {
        return Collections.unmodifiableList(eventLogs);
    }

    public synchronized Map<Integer, PimcAiPlayer.DecisionResult> getLastAiThoughts() {
        return Collections.unmodifiableMap(lastAiThoughts);
    }

    public synchronized Move[] getPlayerLastActions() {
        return playerLastActions.clone();
    }

    public synchronized void addLog(String log) {
        eventLogs.add(log);
        if (eventLogs.size() > 100) {
            eventLogs.remove(0);
        }
    }

    /**
     * 真人玩家出牌
     */
    public synchronized String humanPlay(List<String> cardSymbols) {
        if (gameState.isGameOver()) {
            return "游戏已结束，请重新开局";
        }
        if (gameState.getActivePlayerIndex() != 0) {
            return "当前不是你的回合，请等待 AI 行动";
        }

        List<Move> legalMoves = gameState.getLegalMoves();
        List<Rank> targetCards = new ArrayList<>();
        for (String s : cardSymbols) {
            try {
                targetCards.add(Rank.fromSymbol(s));
            } catch (Exception e) {
                return "无效的卡牌点数: " + s;
            }
        }
        targetCards.sort((a, b) -> Integer.compare(a.getValue(), b.getValue()));

        Move matchedMove = null;
        for (Move m : legalMoves) {
            if (!m.isPass() && m.getCards().equals(targetCards)) {
                matchedMove = m;
                break;
            }
        }

        if (matchedMove == null) {
            return "不符合跑得快规则或压不过上家牌！请重新选牌或查看提示。";
        }

        // 如果是新一轮主动出牌 (自由出牌)，清空上一轮各玩家面前遗留的出牌与不要
        if (gameState.getLastMove() == null || gameState.getLastMove().isPass()) {
            Arrays.fill(playerLastActions, null);
        }

        playerLastActions[0] = matchedMove;
        gameState.applyMove(matchedMove);
        addLog(String.format("👉 玩家 P0 (真人) 出牌: %s (%s)", matchedMove.toCardString(), matchedMove.getType().getDescription()));

        if (gameState.isGameOver()) {
            addLog("👑 恭喜你！手牌已全部出完，获得胜利！🎉");
        }
        return null;
    }

    /**
     * 真人玩家过牌
     */
    public synchronized String humanPass() {
        if (gameState.isGameOver()) {
            return "游戏已结束，请重新开局";
        }
        if (gameState.getActivePlayerIndex() != 0) {
            return "当前不是你的回合";
        }

        List<Move> legalMoves = gameState.getLegalMoves();
        Move passMove = legalMoves.stream().filter(Move::isPass).findFirst().orElse(null);
        if (passMove == null) {
            return "跑得快规则：能管必管，手牌有能压制的牌时不可过牌！";
        }

        playerLastActions[0] = passMove;
        gameState.applyMove(passMove);
        addLog("👉 玩家 P0 (真人) 选择了【不出/过牌】。");
        return null;
    }

    /**
     * 触发单个 AI 步进决策
     */
    public synchronized String aiStep() {
        if (gameState.isGameOver()) {
            return "游戏已结束";
        }
        int activeId = gameState.getActivePlayerIndex();
        if (activeId == 0) {
            return "轮到真人行动";
        }

        // 如果是新一轮主动出牌 (自由出牌)，清空上一轮各玩家面前遗留的出牌与不要
        if (gameState.getLastMove() == null || gameState.getLastMove().isPass()) {
            Arrays.fill(playerLastActions, null);
        }

        PublicView view = gameState.getPublicView(activeId);
        PimcAiPlayer ai = (activeId == 1) ? aiPlayer1 : aiPlayer2;

        PimcAiPlayer.DecisionResult result = ai.decide(view);
        Move chosen = result.getSelectedMove();
        lastAiThoughts.put(activeId, result);

        playerLastActions[activeId] = chosen;
        gameState.applyMove(chosen);

        if (chosen.isPass()) {
            addLog(String.format("🤖 玩家 P%d (AI) 选择了【不出/过牌】", activeId));
        } else {
            addLog(String.format("🤖 玩家 P%d (AI) 出牌: %s (%s)",
                    activeId, chosen.toCardString(), chosen.getType().getDescription()));
        }

        if (gameState.isGameOver()) {
            addLog(String.format("🏆 玩家 P%d (AI) 率先出完手牌，赢得本局比赛！", activeId));
        }

        return chosen.toCardString();
    }

    /**
     * 获取 AI 给真人玩家的推荐出牌
     */
    public synchronized Move getHumanHint() {
        if (gameState.isGameOver() || gameState.getActivePlayerIndex() != 0) {
            return null;
        }
        PublicView view = gameState.getPublicView(0);
        PimcAiPlayer.DecisionResult result = hintAi.decide(view);
        return result.getSelectedMove();
    }
}

package com.paodekuai.game;

import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import com.paodekuai.rules.MoveGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * 完整牌局状态机 (支持深拷贝以支撑 MCTS 快速推演)
 */
public class GameState {
    private final Player[] players = new Player[3];
    private int activePlayerIndex;
    private Move lastMove;
    private int lastMovePlayerId;
    private int passCount;
    private boolean isGameOver;
    private int winnerId = -1;
    private final List<Move> moveHistory;
    private final boolean mustBeat;

    public GameState(List<Hand> hands, int startingPlayer, boolean mustBeat) {
        if (hands.size() != 3) {
            throw new IllegalArgumentException("GameState requires exactly 3 hands");
        }
        for (int i = 0; i < 3; i++) {
            this.players[i] = new Player(i, "Player-" + i, hands.get(i).copy(), true);
        }
        this.activePlayerIndex = startingPlayer;
        this.lastMove = null;
        this.lastMovePlayerId = -1;
        this.passCount = 0;
        this.isGameOver = false;
        this.moveHistory = new ArrayList<>();
        this.mustBeat = mustBeat;
    }

    private GameState(GameState other) {
        for (int i = 0; i < 3; i++) {
            this.players[i] = other.players[i].copy();
        }
        this.activePlayerIndex = other.activePlayerIndex;
        this.lastMove = other.lastMove;
        this.lastMovePlayerId = other.lastMovePlayerId;
        this.passCount = other.passCount;
        this.isGameOver = other.isGameOver;
        this.winnerId = other.winnerId;
        this.moveHistory = new ArrayList<>(other.moveHistory);
        this.mustBeat = other.mustBeat;
    }

    public GameState copy() {
        return new GameState(this);
    }

    public List<Move> getLegalMoves() {
        if (isGameOver) {
            return List.of();
        }
        Player current = players[activePlayerIndex];
        return MoveGenerator.generateLegalMoves(current.getHand(), lastMove, activePlayerIndex, mustBeat);
    }

    /**
     * 执行一个动作并推进游戏状态
     */
    public void applyMove(Move move) {
        if (isGameOver) {
            throw new IllegalStateException("Game is already over");
        }

        Player current = players[activePlayerIndex];
        current.getHand().play(move);
        moveHistory.add(move);

        // 检查终局
        if (current.hasWon()) {
            isGameOver = true;
            winnerId = current.getId();
            return;
        }

        if (move.isPass()) {
            passCount++;
            if (passCount == 2) {
                // 两家都过牌，本轮结束，由上一轮最大出牌者重新出牌 (Lead)
                activePlayerIndex = lastMovePlayerId;
                lastMove = null;
                lastMovePlayerId = -1;
                passCount = 0;
            } else {
                activePlayerIndex = (activePlayerIndex + 1) % 3;
            }
        } else {
            lastMove = move;
            lastMovePlayerId = current.getId();
            passCount = 0;
            activePlayerIndex = (activePlayerIndex + 1) % 3;
        }
    }

    public Player[] getPlayers() {
        return players;
    }

    public Player getPlayer(int id) {
        return players[id];
    }

    public Player getActivePlayer() {
        return players[activePlayerIndex];
    }

    public int getActivePlayerIndex() {
        return activePlayerIndex;
    }

    public Move getLastMove() {
        return lastMove;
    }

    public int getLastMovePlayerId() {
        return lastMovePlayerId;
    }

    public int getPassCount() {
        return passCount;
    }

    public boolean isGameOver() {
        return isGameOver;
    }

    public int getWinnerId() {
        return winnerId;
    }

    public List<Move> getMoveHistory() {
        return moveHistory;
    }

    public boolean isMustBeat() {
        return mustBeat;
    }

    /**
     * 生成供当前行动玩家观察的“不完全信息公开视角”
     */
    public PublicView getPublicView(int viewingPlayerId) {
        int[] counts = new int[3];
        for (int i = 0; i < 3; i++) {
            counts[i] = players[i].getCardCount();
        }
        return new PublicView(
                viewingPlayerId,
                players[viewingPlayerId].getHand().copy(),
                counts,
                new ArrayList<>(moveHistory),
                lastMove,
                lastMovePlayerId,
                activePlayerIndex,
                passCount,
                mustBeat
        );
    }
}

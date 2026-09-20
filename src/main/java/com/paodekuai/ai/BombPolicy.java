package com.paodekuai.ai;

import com.paodekuai.game.GameState;
import com.paodekuai.game.Player;
import com.paodekuai.game.PublicView;
import com.paodekuai.model.Move;

import java.util.List;

/**
 * 炸弹使用先验：非紧急局面坚决留着控场，避免早炸翻车。
 * （跑得快无王炸/阵营，三人混战；能管必管时“安全替代”多为非炸可压牌。）
 */
public final class BombPolicy {

    /** 普通炸弹在非紧急时的胜率惩罚 */
    public static final double BOMB_WINRATE_PENALTY = 0.18;
    /** 普通炸弹访问量折扣 */
    public static final double BOMB_VISIT_SCALE = 0.25;

    private BombPolicy() {
    }

    public static boolean isBomb(Move move) {
        return move != null && move.isBomb();
    }

    /**
     * 基于完全信息状态判断：当前是否属于「值得考虑炸弹」的紧急局面。
     */
    public static boolean isBombUrgent(GameState state) {
        Move lastMove = state.getLastMove();
        Player me = state.getActivePlayer();
        int myCards = me.getCardCount();

        // 主动出牌：有非炸可出时绝不紧急炸
        if (lastMove == null || lastMove.isPass()) {
            return false;
        }

        int lastPlayerId = state.getLastMovePlayerId();
        Player lastPlayer = state.getPlayer(lastPlayerId);
        int lastPlayerCards = lastPlayer.getCardCount();

        // 上家报单/报双，必须封堵
        if (lastPlayerCards <= 2) {
            return true;
        }
        // 自己牌很少，需要夺权冲刺
        if (myCards <= 3) {
            return true;
        }
        // 场上已是炸弹，只能硬刚
        if (lastMove.isBomb()) {
            return true;
        }
        // 任意对手进入绝杀威胁区
        for (int i = 0; i < 3; i++) {
            if (i == me.getId()) {
                continue;
            }
            if (state.getPlayer(i).getCardCount() <= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 基于不完全信息视角判断紧急度（供最终决策先验使用）。
     */
    public static boolean isBombUrgent(PublicView view) {
        Move lastMove = view.getLastMove();
        int myId = view.getViewingPlayerId();
        int myCards = view.getMyHand().getTotalCards();

        if (lastMove == null || lastMove.isPass()) {
            return false;
        }

        int lastPlayerId = view.getLastMovePlayerId();
        if (view.getCardCount(lastPlayerId) <= 2) {
            return true;
        }
        if (myCards <= 3) {
            return true;
        }
        if (lastMove.isBomb()) {
            return true;
        }

        for (int i = 0; i < 3; i++) {
            if (i == myId) {
                continue;
            }
            if (view.getCardCount(i) <= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 候选剪枝：非紧急且仍有普通出法或可过牌时，不把炸弹放进搜索树。
     * 例外：炸弹本身就是一手清空获胜、或别无选择只能炸。
     */
    public static boolean shouldKeepBombCandidates(GameState state, List<Move> legalMoves) {
        int myCards = state.getActivePlayer().getCardCount();
        for (Move m : legalMoves) {
            if (isBomb(m) && m.getCardCount() == myCards) {
                return true;
            }
        }

        boolean hasNonBombPlay = false;
        boolean hasPass = false;
        for (Move m : legalMoves) {
            if (m.isPass()) {
                hasPass = true;
            } else if (!isBomb(m)) {
                hasNonBombPlay = true;
            }
        }

        // 无普通牌可出且不能过 → 被迫炸（含能管必管）
        if (!hasNonBombPlay && !hasPass) {
            return true;
        }

        return isBombUrgent(state);
    }

    /**
     * 决策层综合分：非紧急时对炸弹施加强先验惩罚。
     */
    public static double adjustedScore(Move move, int visits, double winRate, boolean urgent,
                                      boolean hasSafeAlternative) {
        double adjVisits = visits;
        double adjWinRate = winRate;

        if (!urgent && isBomb(move) && hasSafeAlternative) {
            adjWinRate -= BOMB_WINRATE_PENALTY;
            adjVisits *= BOMB_VISIT_SCALE;
        }

        return adjVisits + adjWinRate * 80.0;
    }

    public static boolean hasSafeAlternative(List<Move> legalMoves) {
        for (Move m : legalMoves) {
            if (m.isPass() || !m.isBomb()) {
                return true;
            }
        }
        return false;
    }
}

package com.paodekuai.ai;

import com.paodekuai.game.GameState;
import com.paodekuai.model.CardType;
import com.paodekuai.model.Move;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 快速模拟策略 (Rollout Policy)
 * 使用轻量启发式规则快速对局至终局，兼顾速度与对局合理性
 */
public class FastRolloutPolicy {

    public static int simulate(GameState state, Random random) {
        int movesCount = 0;
        int maxMoves = 200; // 防止异常死循环保护

        while (!state.isGameOver() && movesCount++ < maxMoves) {
            List<Move> legalMoves = state.getLegalMoves();
            if (legalMoves.isEmpty()) {
                break;
            }

            Move chosen = selectRolloutMove(state, legalMoves, random);
            state.applyMove(chosen);
        }

        return state.getWinnerId();
    }

    private static Move selectRolloutMove(GameState state, List<Move> legalMoves, Random random) {
        if (legalMoves.size() == 1) {
            return legalMoves.get(0);
        }

        Move lastMove = state.getLastMove();

        if (lastMove == null || lastMove.isPass()) {
            // 主动出牌：优先多张组合、优先出小牌、避免开局直接甩炸弹
            List<Move> nonBombs = new ArrayList<>();
            List<Move> bombs = new ArrayList<>();
            for (Move m : legalMoves) {
                if (m.isBomb()) {
                    bombs.add(m);
                } else {
                    nonBombs.add(m);
                }
            }

            if (!nonBombs.isEmpty()) {
                // 按主牌点数升序排序，优先垫出小点数的组合或单牌
                nonBombs.sort(Comparator.comparingInt(Move::getMainRank));
                // 优先考虑多张牌型 (如顺子、连对、飞机、三带、对子)
                for (Move m : nonBombs) {
                    if (m.getCardCount() >= 2) {
                        return m;
                    }
                }
                return nonBombs.get(0);
            } else {
                return bombs.get(0);
            }
        } else {
            // 被动应牌：如果有能管上的非炸弹，优先出点数最小的非炸弹
            List<Move> normalBeaters = new ArrayList<>();
            List<Move> bombs = new ArrayList<>();
            Move passMove = null;

            for (Move m : legalMoves) {
                if (m.isPass()) {
                    passMove = m;
                } else if (m.isBomb()) {
                    bombs.add(m);
                } else {
                    normalBeaters.add(m);
                }
            }

            if (!normalBeaters.isEmpty()) {
                normalBeaters.sort(Comparator.comparingInt(Move::getMainRank));
                return normalBeaters.get(0); // 最小压牌
            }

            if (!bombs.isEmpty()) {
                // 上家手牌很少时（<=3张）积极放炸弹拦截
                int lastPlayerCards = state.getPlayer(state.getLastMovePlayerId()).getCardCount();
                if (lastPlayerCards <= 3 || passMove == null || random.nextDouble() < 0.25) {
                    bombs.sort(Comparator.comparingInt(Move::getMainRank));
                    return bombs.get(0);
                }
            }

            return (passMove != null) ? passMove : legalMoves.get(0);
        }
    }
}

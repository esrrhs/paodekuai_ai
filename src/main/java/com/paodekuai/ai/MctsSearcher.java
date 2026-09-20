package com.paodekuai.ai;

import com.paodekuai.game.GameState;
import com.paodekuai.model.CardType;
import com.paodekuai.model.Move;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 蒙特卡洛树搜索器 (在确定化完全信息世界中执行)
 * 支持动作候选剪枝：非紧急局面剔除炸弹，把算力留给控场与过牌。
 */
public class MctsSearcher {
    private final double explorationParam;
    private final Random random;

    public MctsSearcher(double explorationParam, Random random) {
        this.explorationParam = explorationParam;
        this.random = random;
    }

    public MctsSearcher() {
        this(Math.sqrt(2.0), new Random());
    }

    public double getExplorationParam() {
        return explorationParam;
    }

    /**
     * 对指定的确定化状态执行 MCTS 搜索
     */
    public MctsNode search(GameState state, int iterations) {
        List<Move> rootLegalMoves = state.getLegalMoves();
        List<Move> prunedRootMoves = pruneCandidateMoves(rootLegalMoves, state, 15);
        MctsNode root = new MctsNode(null, -1, state.getActivePlayerIndex(), null, prunedRootMoves);

        if (prunedRootMoves.size() <= 1) {
            return root;
        }

        for (int i = 0; i < iterations; i++) {
            GameState simState = state.copy();
            MctsNode node = root;

            // 1. Selection (向下遍历至未完全扩展节点或终局)
            while (node.isFullyExpanded() && !node.getChildren().isEmpty() && !simState.isGameOver()) {
                node = node.selectChild(explorationParam, random);
                simState.applyMove(node.getMove());
            }

            // 2. Expansion (扩展一个未探索的动作)
            if (!simState.isGameOver() && !node.getUntriedMoves().isEmpty()) {
                Move untriedMove = node.getUntriedMoves().get(random.nextInt(node.getUntriedMoves().size()));
                simState.applyMove(untriedMove);
                List<Move> nextLegalMoves = pruneCandidateMoves(simState.getLegalMoves(), simState, 10);
                node = node.expand(untriedMove, simState.getActivePlayerIndex(), nextLegalMoves);
            }

            // 3. Simulation / Rollout (快速推演至终局)
            int winnerId;
            if (simState.isGameOver()) {
                winnerId = simState.getWinnerId();
            } else {
                winnerId = FastRolloutPolicy.simulate(simState, random);
            }

            // 4. Backpropagation (反向传播更新收益)
            node.backpropagate(winnerId);
        }

        return root;
    }

    /**
     * 启发式动作候选过滤：提炼 Top 候选，非紧急时剔除炸弹。
     */
    public static List<Move> pruneCandidateMoves(List<Move> moves, GameState state, int maxCandidates) {
        int handCardCount = state.getActivePlayer().getCardCount();
        boolean keepBombs = BombPolicy.shouldKeepBombCandidates(state, moves);

        List<Move> pool = new ArrayList<>(moves.size());
        for (Move m : moves) {
            if (BombPolicy.isBomb(m) && !keepBombs) {
                if (m.getCardCount() == handCardCount) {
                    pool.add(m);
                }
                continue;
            }
            pool.add(m);
        }
        if (pool.isEmpty()) {
            pool = new ArrayList<>(moves);
        }

        if (pool.size() <= maxCandidates) {
            return pool;
        }

        List<Move> selected = new ArrayList<>(maxCandidates);

        // 1. 终局一手清空
        for (Move m : pool) {
            if (!m.isPass() && m.getCardCount() == handCardCount) {
                selected.add(m);
            }
        }

        // 2. PASS
        for (Move m : pool) {
            if (m.isPass()) {
                selected.add(m);
                break;
            }
        }

        // 3. 结构牌
        List<Move> combos = new ArrayList<>();
        for (Move m : pool) {
            if (m.getType() == CardType.STRAIGHT
                    || m.getType() == CardType.CONSECUTIVE_PAIRS
                    || m.getType() == CardType.AIRPLANE
                    || m.getType() == CardType.AIRPLANE_PLUS_SINGLES
                    || m.getType() == CardType.AIRPLANE_PLUS_PAIRS
                    || m.getType() == CardType.TRIPLE_PLUS_ONE
                    || m.getType() == CardType.TRIPLE_PLUS_PAIR
                    || m.getType() == CardType.FOUR_PLUS_TWO
                    || m.getType() == CardType.FOUR_PLUS_TWO_PAIRS) {
                combos.add(m);
            }
        }
        combos.sort(Comparator.comparingInt(Move::getMainRank));
        int takeCombos = Math.min(4, combos.size());
        for (int i = 0; i < takeCombos; i++) {
            if (!selected.contains(combos.get(i))) {
                selected.add(combos.get(i));
            }
        }

        // 4. 普通非炸弹
        List<Move> normals = new ArrayList<>();
        for (Move m : pool) {
            if (!m.isBomb() && !m.isPass() && !selected.contains(m)) {
                normals.add(m);
            }
        }
        normals.sort(Comparator.comparingInt(Move::getMainRank));
        int takeNormals = Math.min(6, normals.size());
        for (int i = 0; i < takeNormals; i++) {
            selected.add(normals.get(i));
        }

        // 5. 紧急时保留炸弹
        if (keepBombs) {
            List<Move> bombs = new ArrayList<>();
            for (Move m : pool) {
                if (m.isBomb() && !selected.contains(m)) {
                    bombs.add(m);
                }
            }
            bombs.sort(Comparator.comparingInt(Move::getMainRank));
            int takeBombs = Math.min(2, bombs.size());
            for (int i = 0; i < takeBombs; i++) {
                selected.add(bombs.get(i));
            }
        }

        return selected.isEmpty() ? pool : selected;
    }
}

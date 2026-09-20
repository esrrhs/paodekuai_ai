package com.paodekuai.ai;

import com.paodekuai.game.GameState;
import com.paodekuai.model.Move;

import java.util.List;
import java.util.Random;

/**
 * 蒙特卡洛树搜索器 (在确定化完全信息世界中执行)
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

    /**
     * 对指定的确定化状态执行 MCTS 搜索
     */
    public MctsNode search(GameState state, int iterations) {
        List<Move> rootLegalMoves = state.getLegalMoves();
        MctsNode root = new MctsNode(null, -1, state.getActivePlayerIndex(), null, rootLegalMoves);

        if (rootLegalMoves.size() <= 1) {
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
                node = node.expand(untriedMove, simState.getActivePlayerIndex(), simState.getLegalMoves());
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
}

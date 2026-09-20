package com.paodekuai.ai;

import com.paodekuai.model.Move;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 蒙特卡洛搜索树节点
 */
public class MctsNode {
    private final Move move; // 到达该节点所执行的动作 (根节点为 null)
    private final int actingPlayerId; // 做出该动作的玩家
    private final int nextPlayerId; // 该节点局面下接下来该行动的玩家
    private final MctsNode parent;
    private final Map<Move, MctsNode> children = new LinkedHashMap<>();
    private final List<Move> untriedMoves;
    private int visits = 0;
    private final double[] totalWins = new double[3]; // 三位玩家的累积胜率收益

    public MctsNode(Move move, int actingPlayerId, int nextPlayerId, MctsNode parent, List<Move> legalMoves) {
        this.move = move;
        this.actingPlayerId = actingPlayerId;
        this.nextPlayerId = nextPlayerId;
        this.parent = parent;
        this.untriedMoves = new ArrayList<>(legalMoves);
    }

    public boolean isFullyExpanded() {
        return untriedMoves.isEmpty();
    }

    public boolean isTerminal() {
        return untriedMoves.isEmpty() && children.isEmpty();
    }

    /**
     * UCB1 选择最优子节点 (针对当前将要行动的玩家 nextPlayerId 最大化收益)
     */
    public MctsNode selectChild(double explorationParam, Random random) {
        MctsNode bestChild = null;
        double bestValue = Double.NEGATIVE_INFINITY;

        double logParentVisits = Math.log(Math.max(1, this.visits));

        for (MctsNode child : children.values()) {
            if (child.visits == 0) {
                return child;
            }

            // 当前行动玩家的平均胜率
            double exploitation = child.totalWins[nextPlayerId] / child.visits;
            double exploration = explorationParam * Math.sqrt(logParentVisits / child.visits);
            // 微小随机扰动打破平局
            double ucbValue = exploitation + exploration + (random.nextDouble() * 1e-6);

            if (ucbValue > bestValue) {
                bestValue = ucbValue;
                bestChild = child;
            }
        }

        return bestChild;
    }

    /**
     * 扩展一个尚未尝试的动作
     */
    public MctsNode expand(Move move, int newNextPlayerId, List<Move> newLegalMoves) {
        untriedMoves.remove(move);
        MctsNode child = new MctsNode(move, nextPlayerId, newNextPlayerId, this, newLegalMoves);
        children.put(move, child);
        return child;
    }

    /**
     * 反向传播更新访问计数与胜负收益
     */
    public void backpropagate(int winnerId) {
        MctsNode current = this;
        while (current != null) {
            current.visits++;
            if (winnerId >= 0 && winnerId < 3) {
                current.totalWins[winnerId] += 1.0;
            }
            current = current.parent;
        }
    }

    public Move getMove() {
        return move;
    }

    public int getActingPlayerId() {
        return actingPlayerId;
    }

    public int getNextPlayerId() {
        return nextPlayerId;
    }

    public MctsNode getParent() {
        return parent;
    }

    public Map<Move, MctsNode> getChildren() {
        return children;
    }

    public List<Move> getUntriedMoves() {
        return untriedMoves;
    }

    public int getVisits() {
        return visits;
    }

    public double[] getTotalWins() {
        return totalWins;
    }

    public double getWinRate(int playerId) {
        return visits == 0 ? 0.0 : totalWins[playerId] / visits;
    }
}

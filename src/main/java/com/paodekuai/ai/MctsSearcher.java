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
 * <p>
 * 剪枝是「推荐顺序 / 算力分配」而非硬性禁令：
 * 优先给小牌、顺子/飞机等结构牌分配搜索预算；候选额度足够时尽量全覆盖；
 * 尾牌阶段启发减弱，合法着法全量进入搜索树。迭代足够时会按序扩展完所有入选着法。
 */
public class MctsSearcher {
    private final double explorationParam;
    private final Random random;

    /** 手牌少于此阈值时不再按启发截断，全量搜索 */
    private static final int ENDGAME_FULL_SEARCH_CARDS = 6;

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

            while (node.isFullyExpanded() && !node.getChildren().isEmpty() && !simState.isGameOver()) {
                node = node.selectChild(explorationParam, random);
                simState.applyMove(node.getMove());
            }

            // 按推荐顺序优先扩展靠前着法；迭代足够时仍会扩展完整列表
            if (!simState.isGameOver() && !node.getUntriedMoves().isEmpty()) {
                Move untriedMove = node.getUntriedMoves().get(0);
                simState.applyMove(untriedMove);
                List<Move> nextLegalMoves = pruneCandidateMoves(simState.getLegalMoves(), simState, 10);
                node = node.expand(untriedMove, simState.getActivePlayerIndex(), nextLegalMoves);
            }

            int winnerId;
            if (simState.isGameOver()) {
                winnerId = simState.getWinnerId();
            } else {
                winnerId = FastRolloutPolicy.simulate(simState, random);
            }

            node.backpropagate(winnerId);
        }

        return root;
    }

    /**
     * 按推荐顺序排列候选；额度不足时截断队尾，额度足够或尾牌阶段则全量保留。
     */
    public static List<Move> pruneCandidateMoves(List<Move> moves, GameState state, int baseMaxCandidates) {
        int handCardCount = state.getActivePlayer().getCardCount();
        boolean endgame = handCardCount <= ENDGAME_FULL_SEARCH_CARDS;
        boolean keepBombs = endgame || BombPolicy.shouldKeepBombCandidates(state, moves);
        int maxCandidates = resolveCandidateBudget(handCardCount, baseMaxCandidates);

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

        List<Move> ordered = prioritizeMoves(pool, handCardCount, keepBombs);
        if (ordered.size() <= maxCandidates) {
            return ordered;
        }
        return new ArrayList<>(ordered.subList(0, maxCandidates));
    }

    static int resolveCandidateBudget(int handCardCount, int baseMaxCandidates) {
        if (handCardCount <= ENDGAME_FULL_SEARCH_CARDS) {
            return Integer.MAX_VALUE;
        }
        if (handCardCount <= 10) {
            return Math.max(baseMaxCandidates, baseMaxCandidates + 6);
        }
        return baseMaxCandidates;
    }

    static List<Move> prioritizeMoves(List<Move> pool, int handCardCount, boolean keepBombs) {
        List<Move> clears = new ArrayList<>();
        List<Move> passes = new ArrayList<>();
        List<Move> structures = new ArrayList<>();
        List<Move> normals = new ArrayList<>();
        List<Move> bombs = new ArrayList<>();

        for (Move m : pool) {
            if (m.isPass()) {
                passes.add(m);
            } else if (m.getCardCount() == handCardCount) {
                clears.add(m);
            } else if (BombPolicy.isBomb(m)) {
                bombs.add(m);
            } else if (isStructureMove(m)) {
                structures.add(m);
            } else {
                normals.add(m);
            }
        }

        structures.sort(Comparator.comparingInt(Move::getMainRank));
        normals.sort(Comparator.comparingInt(Move::getMainRank));
        bombs.sort(Comparator.comparingInt(Move::getMainRank));
        clears.sort(Comparator.comparingInt(Move::getMainRank));

        List<Move> ordered = new ArrayList<>(pool.size());
        addUnique(ordered, clears);
        addUnique(ordered, passes);
        addUnique(ordered, structures);
        addUnique(ordered, normals);
        if (keepBombs) {
            addUnique(ordered, bombs);
        }
        for (Move m : pool) {
            if (!ordered.contains(m)) {
                ordered.add(m);
            }
        }
        return ordered;
    }

    private static void addUnique(List<Move> ordered, List<Move> batch) {
        for (Move m : batch) {
            if (!ordered.contains(m)) {
                ordered.add(m);
            }
        }
    }

    private static boolean isStructureMove(Move m) {
        return m.getType() == CardType.STRAIGHT
                || m.getType() == CardType.CONSECUTIVE_PAIRS
                || m.getType() == CardType.AIRPLANE
                || m.getType() == CardType.AIRPLANE_PLUS_SINGLES
                || m.getType() == CardType.AIRPLANE_PLUS_PAIRS
                || m.getType() == CardType.TRIPLE_PLUS_ONE
                || m.getType() == CardType.TRIPLE_PLUS_PAIR
                || m.getType() == CardType.FOUR_PLUS_TWO
                || m.getType() == CardType.FOUR_PLUS_TWO_PAIRS;
    }
}

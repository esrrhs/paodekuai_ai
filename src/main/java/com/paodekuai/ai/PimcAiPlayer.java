package com.paodekuai.ai;

import com.paodekuai.game.GameState;
import com.paodekuai.game.PublicView;
import com.paodekuai.model.Move;
import com.paodekuai.rules.MoveGenerator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/**
 * PIMC (Perfect Information Monte Carlo) 跑得快 AI 引擎
 * 通过多次对对手底牌进行可能世界采样 (Determinization)，在各世界中运行 MCTS 并聚合决策
 */
public class PimcAiPlayer {
    private final int numDeterminizations;
    private final int mctsIterationsPerWorld;
    private final Random random;
    private final MctsSearcher searcher;

    public PimcAiPlayer(int numDeterminizations, int mctsIterationsPerWorld, Random random) {
        this.numDeterminizations = numDeterminizations;
        this.mctsIterationsPerWorld = mctsIterationsPerWorld;
        this.random = random;
        this.searcher = new MctsSearcher(Math.sqrt(2.0), random);
    }

    public PimcAiPlayer(int numDeterminizations, int mctsIterationsPerWorld) {
        this(numDeterminizations, mctsIterationsPerWorld, new Random());
    }

    public PimcAiPlayer() {
        // 与斗地主端对齐的默认算力：更多假想世界 + 更深 MCTS
        this(240, 1600);
    }

    /**
     * 动作评估数据
     */
    public static class MoveEvaluation {
        private final Move move;
        private int totalVisits = 0;
        private double sumWinRate = 0.0;
        private int sampleCount = 0;

        public MoveEvaluation(Move move) {
            this.move = move;
        }

        public synchronized void record(int visits, double winRate) {
            this.totalVisits += visits;
            this.sumWinRate += winRate;
            this.sampleCount++;
        }

        public Move getMove() {
            return move;
        }

        public int getTotalVisits() {
            return totalVisits;
        }

        public double getAverageWinRate() {
            return sampleCount == 0 ? 0.0 : sumWinRate / sampleCount;
        }

        @Override
        public String toString() {
            return String.format("%s -> 总访问: %4d, 平均胜率: %5.1f%% (采样: %2d次)",
                    move.toCardString(), totalVisits, getAverageWinRate() * 100.0, sampleCount);
        }
    }

    public static class DecisionResult {
        private final Move selectedMove;
        private final List<MoveEvaluation> evaluations;
        private final long durationMillis;

        public DecisionResult(Move selectedMove, List<MoveEvaluation> evaluations, long durationMillis) {
            this.selectedMove = selectedMove;
            this.evaluations = evaluations;
            this.durationMillis = durationMillis;
        }

        public Move getSelectedMove() {
            return selectedMove;
        }

        public List<MoveEvaluation> getEvaluations() {
            return evaluations;
        }

        public long getDurationMillis() {
            return durationMillis;
        }
    }

    /**
     * 根据当前不完全信息观察视角做出出牌决策
     */
    public DecisionResult decide(PublicView publicView) {
        long startTime = System.currentTimeMillis();
        int myId = publicView.getViewingPlayerId();

        List<Move> legalMoves = MoveGenerator.generateLegalMoves(
                publicView.getMyHand(),
                publicView.getLastMove(),
                myId,
                publicView.isMustBeat()
        );

        if (legalMoves.isEmpty()) {
            return new DecisionResult(Move.pass(myId), List.of(), 0);
        }

        // 可一手出完则直接斩杀
        for (Move m : legalMoves) {
            if (!m.isPass() && m.getCardCount() == publicView.getMyHand().getTotalCards()) {
                MoveEvaluation eval = new MoveEvaluation(m);
                eval.record(100, 1.0);
                return new DecisionResult(m, List.of(eval), System.currentTimeMillis() - startTime);
            }
        }

        if (legalMoves.size() == 1) {
            Move singleOption = legalMoves.get(0);
            MoveEvaluation eval = new MoveEvaluation(singleOption);
            // 唯一着法时用少量 rollout 估胜率，避免虚假 100%
            int wins = 0;
            int sampleK = 15;
            for (int i = 0; i < sampleK; i++) {
                GameState world = Determinizer.determinize(publicView, random);
                world.applyMove(singleOption);
                FastRolloutPolicy.simulate(world, random);
                if (world.getWinnerId() == myId) {
                    wins++;
                }
            }
            eval.record(sampleK, (double) wins / sampleK);
            return new DecisionResult(singleOption, List.of(eval), System.currentTimeMillis() - startTime);
        }

        Map<Move, MoveEvaluation> evalMap = new LinkedHashMap<>();
        for (Move m : legalMoves) {
            evalMap.put(m, new MoveEvaluation(m));
        }

        int worldCount = numDeterminizations;
        Determinizer.SampledWorld[] worlds = new Determinizer.SampledWorld[worldCount];
        IntStream.range(0, worldCount).parallel().forEach(k ->
                worlds[k] = Determinizer.sample(publicView, ThreadLocalRandom.current()));

        double[] likelihoods = new double[worldCount];
        for (int i = 0; i < worldCount; i++) {
            likelihoods[i] = worlds[i].likelihood();
        }
        int[] iterations = PassInference.allocateSearchIterations(likelihoods, mctsIterationsPerWorld);
        Integer[] order = new Integer[worldCount];
        for (int i = 0; i < worldCount; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(likelihoods[b], likelihoods[a]));

        java.util.Arrays.stream(order).parallel().forEach(k -> {
            Random workerRandom = ThreadLocalRandom.current();
            MctsSearcher workerSearcher = new MctsSearcher(searcher.getExplorationParam(), workerRandom);
            MctsNode root = workerSearcher.search(worlds[k].state(), iterations[k]);

            for (Map.Entry<Move, MctsNode> entry : root.getChildren().entrySet()) {
                Move move = entry.getKey();
                MctsNode child = entry.getValue();
                MoveEvaluation eval = evalMap.get(move);
                if (eval != null) {
                    eval.record(child.getVisits(), child.getWinRate(myId));
                }
            }
        });

        List<MoveEvaluation> evalList = new ArrayList<>(evalMap.values());
        boolean urgent = BombPolicy.isBombUrgent(publicView);
        boolean hasSafeAlternative = BombPolicy.hasSafeAlternative(legalMoves);
        evalList.sort((a, b) -> {
            double scoreA = BombPolicy.adjustedScore(
                    a.getMove(), a.getTotalVisits(), a.getAverageWinRate(), urgent, hasSafeAlternative);
            double scoreB = BombPolicy.adjustedScore(
                    b.getMove(), b.getTotalVisits(), b.getAverageWinRate(), urgent, hasSafeAlternative);
            int cmp = Double.compare(scoreB, scoreA);
            if (cmp != 0) {
                return cmp;
            }
            return Double.compare(b.getAverageWinRate(), a.getAverageWinRate());
        });

        Move bestMove = evalList.get(0).getMove();
        long duration = System.currentTimeMillis() - startTime;

        return new DecisionResult(bestMove, evalList, duration);
    }
}

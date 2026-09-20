package com.paodekuai.ai;

import com.paodekuai.game.GameState;
import com.paodekuai.game.PublicView;
import com.paodekuai.model.Move;
import com.paodekuai.rules.MoveGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
        this(20, 150);
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

        public void record(int visits, double winRate) {
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

        if (legalMoves.size() == 1) {
            Move singleOption = legalMoves.get(0);
            MoveEvaluation eval = new MoveEvaluation(singleOption);
            eval.record(1, 1.0);
            return new DecisionResult(singleOption, List.of(eval), System.currentTimeMillis() - startTime);
        }

        Map<Move, MoveEvaluation> evalMap = new LinkedHashMap<>();
        for (Move m : legalMoves) {
            evalMap.put(m, new MoveEvaluation(m));
        }

        // 运行 K 次确定化采样
        for (int k = 0; k < numDeterminizations; k++) {
            GameState world = Determinizer.determinize(publicView, random);
            MctsNode root = searcher.search(world, mctsIterationsPerWorld);

            for (Map.Entry<Move, MctsNode> entry : root.getChildren().entrySet()) {
                Move move = entry.getKey();
                MctsNode child = entry.getValue();

                MoveEvaluation eval = evalMap.get(move);
                if (eval != null) {
                    eval.record(child.getVisits(), child.getWinRate(myId));
                }
            }
        }

        List<MoveEvaluation> evalList = new ArrayList<>(evalMap.values());
        // 综合评价：优先访问次数最多、胜率最高的稳健动作
        evalList.sort(Comparator.comparingInt(MoveEvaluation::getTotalVisits).reversed()
                .thenComparing(Comparator.comparingDouble(MoveEvaluation::getAverageWinRate).reversed()));

        Move bestMove = evalList.get(0).getMove();
        long duration = System.currentTimeMillis() - startTime;

        return new DecisionResult(bestMove, evalList, duration);
    }
}

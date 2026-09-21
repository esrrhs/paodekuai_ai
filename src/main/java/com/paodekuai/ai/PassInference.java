package com.paodekuai.ai;

import com.paodekuai.game.PublicView;
import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import com.paodekuai.model.Rank;
import com.paodekuai.rules.MoveGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * 由公开出牌历史做「过牌推断」，剪掉明显不合理的假想世界。
 * <p>
 * 能管必管：过牌 ⇒ 当时绝无任何可压制着法（硬约束）。
 * 非必管：否定「有紧挨的便宜同型可压却过」的世界（与斗地主同思路）。
 */
public final class PassInference {

    private PassInference() {
    }

    public static final class Constraint {
        public final int passerId;
        public final Move challenge;
        public final int historyIndex;

        Constraint(int passerId, Move challenge, int historyIndex) {
            this.passerId = passerId;
            this.challenge = challenge;
            this.historyIndex = historyIndex;
        }
    }

    public static List<Constraint> extractConstraints(List<Move> history) {
        List<Constraint> result = new ArrayList<>();
        Move currentChallenge = null;
        for (int i = 0; i < history.size(); i++) {
            Move m = history.get(i);
            if (m.isPass()) {
                if (currentChallenge != null && !currentChallenge.isPass()) {
                    result.add(new Constraint(m.getPlayerId(), currentChallenge, i));
                }
            } else {
                currentChallenge = m;
            }
        }
        return result;
    }

    public static List<Constraint> extractActiveConstraints(PublicView view) {
        List<Move> history = view.getMoveHistory();
        List<Constraint> all = extractConstraints(history);
        if (all.isEmpty()) {
            return all;
        }
        Move lastMove = view.getLastMove();
        List<Constraint> active = new ArrayList<>();
        for (Constraint c : all) {
            if (lastMove != null && sameChallenge(c.challenge, lastMove)) {
                active.add(c);
            }
        }
        if (active.isEmpty()) {
            int from = Math.max(0, all.size() - 4);
            active.addAll(all.subList(from, all.size()));
        }
        return active;
    }

    private static boolean sameChallenge(Move a, Move b) {
        return a.getType() == b.getType()
                && a.getMainRank() == b.getMainRank()
                && a.getPlayerId() == b.getPlayerId()
                && a.getCardCount() == b.getCardCount();
    }

    public static Hand reconstructHandAtPass(Hand currentHand, int passerId,
                                             List<Move> history, int passIndex) {
        Hand hand = currentHand.copy();
        for (int i = passIndex + 1; i < history.size(); i++) {
            Move m = history.get(i);
            if (m.getPlayerId() == passerId && !m.isPass()) {
                for (Rank r : m.getCards()) {
                    hand.add(r);
                }
            }
        }
        return hand;
    }

    public static double likelihood(PublicView view, List<Hand> hands) {
        int violations = countViolations(view, hands);
        if (violations <= 0) {
            return 1.0;
        }
        return Math.max(0.2, Math.pow(0.45, violations));
    }

    public static int[] allocateSearchIterations(double[] likelihoods, int fullIterations) {
        int n = likelihoods.length;
        double[] weight = new double[n];
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            weight[i] = Math.max(0.25, likelihoods[i]);
            sum += weight[i];
        }
        int[] iterations = new int[n];
        for (int i = 0; i < n; i++) {
            iterations[i] = Math.max(1, (int) Math.round(fullIterations * (n * weight[i] / sum)));
        }
        return iterations;
    }

    public static boolean isWorldConsistent(PublicView view, List<Hand> hands) {
        return countViolations(view, hands) == 0;
    }

    private static int countViolations(PublicView view, List<Hand> hands) {
        int myId = view.getViewingPlayerId();
        List<Move> history = view.getMoveHistory();
        List<Constraint> constraints = extractActiveConstraints(view);
        if (constraints.isEmpty()) {
            return 0;
        }

        boolean mustBeat = view.isMustBeat();
        int violations = 0;
        for (Constraint c : constraints) {
            if (c.passerId == myId) {
                continue;
            }
            Hand handAtPass = reconstructHandAtPass(
                    hands.get(c.passerId), c.passerId, history, c.historyIndex);
            if (mustBeat) {
                if (hasAnyBeater(handAtPass, c.challenge, c.passerId, true)) {
                    violations += 2;
                }
            } else if (hasObviousCheapBeater(handAtPass, c.challenge, c.passerId)) {
                violations++;
            }
        }
        return violations;
    }

    static boolean hasAnyBeater(Hand hand, Move challenge, int playerId, boolean mustBeat) {
        List<Move> legal = MoveGenerator.generateLegalMoves(hand, challenge, playerId, mustBeat);
        for (Move m : legal) {
            if (!m.isPass()) {
                return true;
            }
        }
        return false;
    }

    static boolean hasObviousCheapBeater(Hand hand, Move challenge, int playerId) {
        if (challenge == null || challenge.isPass()) {
            return false;
        }
        List<Move> legal = MoveGenerator.generateLegalMoves(hand, challenge, playerId, false);
        for (Move m : legal) {
            if (m.isPass() || m.isBomb()) {
                continue;
            }
            if (!m.canBeat(challenge) || m.getType() != challenge.getType()) {
                continue;
            }
            switch (challenge.getType()) {
                case SINGLE -> {
                    int r = m.getMainRank();
                    int maxCheap = Math.min(Rank.TEN.getValue(), challenge.getMainRank() + 3);
                    if (r <= maxCheap && r > challenge.getMainRank() && hand.getCount(r) == 1) {
                        return true;
                    }
                }
                case PAIR -> {
                    int r = m.getMainRank();
                    int maxCheap = Math.min(Rank.QUEEN.getValue(), challenge.getMainRank() + 3);
                    if (r <= maxCheap && r > challenge.getMainRank()) {
                        return true;
                    }
                }
                case TRIPLE, TRIPLE_PLUS_ONE, TRIPLE_PLUS_PAIR -> {
                    int r = m.getMainRank();
                    int maxCheap = Math.min(Rank.JACK.getValue(), challenge.getMainRank() + 2);
                    if (r <= maxCheap && r > challenge.getMainRank()) {
                        return true;
                    }
                }
                case STRAIGHT, CONSECUTIVE_PAIRS, AIRPLANE, AIRPLANE_PLUS_SINGLES,
                     AIRPLANE_PLUS_PAIRS, FOUR_PLUS_TWO, FOUR_PLUS_TWO_PAIRS -> {
                    if (m.getMainRank() <= challenge.getMainRank() + 2) {
                        return true;
                    }
                }
                default -> {
                }
            }
        }
        return false;
    }
}

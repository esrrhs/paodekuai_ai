package com.paodekuai;

import com.paodekuai.ai.AiDecision;
import com.paodekuai.ai.NeuralAiPlayer;
import com.paodekuai.game.GameState;
import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import com.paodekuai.rules.Deck;

import java.nio.file.Path;
import java.util.List;

/**
 * 命令行演示:三个策略网络 AI(共享同一份权重)自对弈。
 * 也可作为自对弈评估的基准入口。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path modelDir = NeuralAiPlayer.defaultModelDir();
        int games = 1;
        long seed = 1;
        boolean verbose = true;
        for (int i = 0; i < args.length; ++i) {
            switch (args[i]) {
                case "--model-dir" -> modelDir = Path.of(args[++i]);
                case "--games" -> games = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--quiet" -> verbose = false;
                default -> {
                    System.err.println("unknown arg: " + args[i]);
                    System.err.println("usage: Main [--model-dir DIR] [--games N] [--seed N] [--quiet]");
                    System.exit(2);
                }
            }
        }

        try (NeuralAiPlayer ai = new NeuralAiPlayer(com.paodekuai.nn.ActorNetwork.load(modelDir))) {
            int[] wins = new int[3];
            double totalPayoff = 0.0;
            for (int g = 0; g < games; ++g) {
                Deck.Deal deal = Deck.deal(new Deck.Rng(seed + g));
                GameState state = new GameState(deal.hands(), deal.leader());
                if (verbose) {
                    System.out.printf("== game %d, leader p%d ==%n", g, deal.leader());
                    for (int s = 0; s < 3; ++s) {
                        System.out.printf("  p%d hand %s%n", s, state.getHand(s));
                    }
                }
                int guard = 0;
                while (!state.isOver() && guard++ < 10000) {
                    AiDecision d = ai.decide(state.getPublicView(state.getTurn()));
                    Move move = d.selectedMove();
                    if (verbose) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(String.format("  p%d %-14s", state.getTurn(),
                                move.isPass() ? "PASS" : move.toString()));
                        sb.append(String.format("(%d 候选, %.1f%%, %d ms)",
                                d.moveScores().size(),
                                d.moveScores().isEmpty() ? 100.0
                                        : d.moveScores().get(0).probability() * 100,
                                d.durationMillis()));
                        System.out.println(sb);
                    }
                    state.step(move);
                }
                if (verbose) {
                    for (int s = 0; s < 3; ++s) {
                        System.out.printf("  p%d left %d: %s%n", s,
                                state.getHand(s).getTotal(), state.getHand(s));
                    }
                }
                int winner = state.getWinner();
                wins[winner]++;
                totalPayoff += state.payoff(0);
                if (verbose) {
                    System.out.printf("  winner p%d, payoff(p0)=%.3f%n", winner, state.payoff(0));
                }
            }
            System.out.printf("games %d, wins p0/p1/p2 = %d/%d/%d, total payoff(p0) %.3f%n",
                    games, wins[0], wins[1], wins[2], totalPayoff);
        }
    }
}

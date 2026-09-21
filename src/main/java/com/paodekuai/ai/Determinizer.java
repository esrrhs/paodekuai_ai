package com.paodekuai.ai;

import com.paodekuai.game.GameState;
import com.paodekuai.game.PublicView;
import com.paodekuai.model.Hand;
import com.paodekuai.model.Rank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 确定化采样器 (Determinizer)
 * <p>
 * 未知牌池 = 48 张 − 自己手牌 − 已打出牌，再按剩余张数分给两名对手。
 * 采样一次并给出过牌推断似然。上层按概率分配搜索算力，不再丢弃低概率世界。
 */
public class Determinizer {

    public static GameState determinize(PublicView publicView, Random random) {
        return sample(publicView, random).state();
    }

    public record SampledWorld(GameState state, double likelihood) {
    }

    public static SampledWorld sample(PublicView publicView, Random random) {
        List<Hand> hands = sampleHands(publicView, random);
        double likelihood = PassInference.likelihood(publicView, hands);
        return new SampledWorld(buildState(publicView, hands), likelihood);
    }

    private static List<Hand> sampleHands(PublicView publicView, Random random) {
        int myId = publicView.getViewingPlayerId();
        List<Rank> unseen = new ArrayList<>(publicView.computeUnseenCards());
        Collections.shuffle(unseen, random);

        List<Hand> hands = new ArrayList<>(3);
        hands.add(new Hand());
        hands.add(new Hand());
        hands.add(new Hand());
        hands.set(myId, publicView.getMyHand().copy());

        int unseenIndex = 0;
        for (int i = 0; i < 3; i++) {
            if (i != myId) {
                int needed = publicView.getCardCount(i);
                Hand oppHand = hands.get(i);
                for (int j = 0; j < needed; j++) {
                    if (unseenIndex >= unseen.size()) {
                        throw new IllegalStateException("Unseen card pool exhausted during determinization");
                    }
                    oppHand.add(unseen.get(unseenIndex++));
                }
            }
        }
        return hands;
    }

    private static GameState buildState(PublicView publicView, List<Hand> hands) {
        GameState simulatedState = new GameState(hands, publicView.getActivePlayerId(), publicView.isMustBeat());
        try {
            var lastMoveField = GameState.class.getDeclaredField("lastMove");
            lastMoveField.setAccessible(true);
            lastMoveField.set(simulatedState, publicView.getLastMove());

            var lastMovePlayerField = GameState.class.getDeclaredField("lastMovePlayerId");
            lastMovePlayerField.setAccessible(true);
            lastMovePlayerField.set(simulatedState, publicView.getLastMovePlayerId());

            var passCountField = GameState.class.getDeclaredField("passCount");
            passCountField.setAccessible(true);
            passCountField.set(simulatedState, publicView.getPassCount());
        } catch (Exception e) {
            throw new RuntimeException("Failed to reconstruct trick state in determinization", e);
        }
        return simulatedState;
    }
}

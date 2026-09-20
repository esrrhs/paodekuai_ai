package com.paodekuai.ai;

import com.paodekuai.game.GameState;
import com.paodekuai.game.PublicView;
import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import com.paodekuai.model.Rank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 确定化采样器 (Determinizer)
 * 将不完全信息的公开视角转化为一个具体的、完全信息的假想牌局状态 (World Sample)
 */
public class Determinizer {

    public static GameState determinize(PublicView publicView, Random random) {
        int myId = publicView.getViewingPlayerId();
        List<Rank> unseen = new ArrayList<>(publicView.computeUnseenCards());
        Collections.shuffle(unseen, random);

        List<Hand> hands = new ArrayList<>(3);
        hands.add(new Hand());
        hands.add(new Hand());
        hands.add(new Hand());

        // 恢复自己的真实手牌
        hands.set(myId, publicView.getMyHand().copy());

        // 将未知牌池分配给其余两位对手
        int unseenIndex = 0;
        for (int i = 0; i < 3; i++) {
            if (i != myId) {
                int needed = publicView.getCardCount(i);
                Hand oppHand = hands.get(i);
                for (int j = 0; j < needed; j++) {
                    if (unseenIndex < unseen.size()) {
                        oppHand.add(unseen.get(unseenIndex++));
                    }
                }
            }
        }

        // 构建假想状态
        GameState simulatedState = new GameState(hands, publicView.getActivePlayerId(), publicView.isMustBeat());

        // 重建桌面轮次与最近出牌状态
        reconstructTrickState(simulatedState, publicView);

        return simulatedState;
    }

    private static void reconstructTrickState(GameState state, PublicView publicView) {
        // 通过反射或者 package-private 方式恢复 trick 状态更干净；
        // 直接按公共信息对齐桌面状态
        try {
            var lastMoveField = GameState.class.getDeclaredField("lastMove");
            lastMoveField.setAccessible(true);
            lastMoveField.set(state, publicView.getLastMove());

            var lastMovePlayerField = GameState.class.getDeclaredField("lastMovePlayerId");
            lastMovePlayerField.setAccessible(true);
            lastMovePlayerField.set(state, publicView.getLastMovePlayerId());

            var passCountField = GameState.class.getDeclaredField("passCount");
            passCountField.setAccessible(true);
            passCountField.set(state, publicView.getPassCount());
        } catch (Exception e) {
            throw new RuntimeException("Failed to reconstruct trick state in determinization", e);
        }
    }
}

package com.paodekuai.ai;

import com.paodekuai.game.GameState;
import com.paodekuai.game.PublicView;
import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import com.paodekuai.model.Rank;
import com.paodekuai.rules.Deck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class PimcAITest {

    @Test
    @DisplayName("测试确定化采样器 (Determinizer) 的手牌数量与未知牌守恒性")
    void testDeterminizerConservation() {
        Random random = new Random(42);
        List<Hand> hands = Deck.deal(random);
        GameState state = new GameState(hands, 0, true);

        PublicView view = state.getPublicView(0);
        List<Rank> unseen = view.computeUnseenCards();

        // 另外两家各有16张手牌，所以未知牌数必须等于 32
        assertThat(unseen).hasSize(32);

        // 确定化生成假想状态
        GameState sampledWorld = Determinizer.determinize(view, random);

        // 验证玩家 0 的手牌与真实一致
        assertThat(sampledWorld.getPlayer(0).getHand().getTotalCards()).isEqualTo(16);
        assertThat(sampledWorld.getPlayer(0).getHand().toCardString()).isEqualTo(hands.get(0).toCardString());

        // 验证对手分配到的张数完全符合公开信息
        assertThat(sampledWorld.getPlayer(1).getHand().getTotalCards()).isEqualTo(16);
        assertThat(sampledWorld.getPlayer(2).getHand().getTotalCards()).isEqualTo(16);
    }

    @Test
    @DisplayName("测试 AI 在仅剩必胜牌时的决策准确度")
    void testWinningMoveSelection() {
        Hand myHand = Deck.fromCardString("2"); // 我只剩一张 2
        Hand opp1 = Deck.fromCardString("3,4");
        Hand opp2 = Deck.fromCardString("5,6");

        GameState state = new GameState(List.of(myHand, opp1, opp2), 0, true);
        PublicView view = state.getPublicView(0);

        PimcAiPlayer ai = new PimcAiPlayer(5, 50);
        PimcAiPlayer.DecisionResult result = ai.decide(view);

        // 必须直接出 2 获胜
        assertThat(result.getSelectedMove().toCardString()).isEqualTo("2");
    }
}

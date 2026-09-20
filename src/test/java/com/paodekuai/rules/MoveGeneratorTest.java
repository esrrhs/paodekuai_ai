package com.paodekuai.rules;

import com.paodekuai.model.CardType;
import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import com.paodekuai.model.Rank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MoveGeneratorTest {

    @Test
    @DisplayName("主动出牌生成测试")
    void testLeadMovesGeneration() {
        Hand hand = Deck.fromCardString("3,3,4,5,6,7,8,8,8,8");
        List<Move> moves = MoveGenerator.generateLegalMoves(hand, null, 0, true);

        assertThat(moves).isNotEmpty();

        // 包含炸弹 8
        boolean hasBomb8 = moves.stream()
                .anyMatch(m -> m.isBomb() && m.getMainRank() == Rank.EIGHT.getValue());
        assertThat(hasBomb8).isTrue();

        // 包含对子 3
        boolean hasPair3 = moves.stream()
                .anyMatch(m -> m.getType() == CardType.PAIR && m.getMainRank() == Rank.THREE.getValue());
        assertThat(hasPair3).isTrue();

        // 包含顺子 3,4,5,6,7
        boolean hasStraight = moves.stream()
                .anyMatch(m -> m.getType() == CardType.STRAIGHT && m.getMainRank() == Rank.SEVEN.getValue() && m.getCardCount() == 5);
        assertThat(hasStraight).isTrue();
    }

    @Test
    @DisplayName("压牌测试：同牌型更大点数与炸弹压制")
    void testBeatingMoves() {
        Hand hand = Deck.fromCardString("4,4,5,9,9,9,9,K,2");

        // 场上是对 3
        Move lastPair3 = Move.of(CardType.PAIR, Rank.THREE.getValue(), List.of(Rank.THREE, Rank.THREE), 1);
        List<Move> beatMoves = MoveGenerator.generateLegalMoves(hand, lastPair3, 0, true);

        // 可以出对 4，也可以出 9 炸弹
        assertThat(beatMoves).anyMatch(m -> m.getType() == CardType.PAIR && m.getMainRank() == Rank.FOUR.getValue());
        assertThat(beatMoves).anyMatch(m -> m.isBomb() && m.getMainRank() == Rank.NINE.getValue());

        // 不能过牌 (因为开启了能管必管)
        assertThat(beatMoves).noneMatch(Move::isPass);
    }

    @Test
    @DisplayName("压牌测试：无法压制时返回 PASS")
    void testPassWhenCannotBeat() {
        Hand hand = Deck.fromCardString("3,4,5");

        // 场上是对 K
        Move lastPairK = Move.of(CardType.PAIR, Rank.KING.getValue(), List.of(Rank.KING, Rank.KING), 1);
        List<Move> beatMoves = MoveGenerator.generateLegalMoves(hand, lastPairK, 0, true);

        assertThat(beatMoves).hasSize(1);
        assertThat(beatMoves.get(0).isPass()).isTrue();
    }
}

package com.paodekuai.rules;

import com.paodekuai.model.CardType;
import com.paodekuai.model.Hand;
import com.paodekuai.rules.MoveGenerator.MoveInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 牌型检测与合法动作生成的关键语义(与训练侧一致) */
class MoveGeneratorTest {

    @Test
    void detectsBasicPatterns() {
        assertThat(MoveGenerator.detectMove(Hand.parse("3")).type()).isEqualTo(CardType.SINGLE);
        assertThat(MoveGenerator.detectMove(Hand.parse("33")).type()).isEqualTo(CardType.PAIR);
        assertThat(MoveGenerator.detectMove(Hand.parse("333")).type()).isEqualTo(CardType.TRIPLE);
        assertThat(MoveGenerator.detectMove(Hand.parse("3333")).type()).isEqualTo(CardType.BOMB);
        assertThat(MoveGenerator.detectMove(Hand.parse("33344")).type()).isEqualTo(CardType.TRIPLE_TWO);
        assertThat(MoveGenerator.detectMove(Hand.parse("34567")).type()).isEqualTo(CardType.STRAIGHT);
        assertThat(MoveGenerator.detectMove(Hand.parse("3344")).type()).isEqualTo(CardType.PAIR_STRAIGHT);
        assertThat(MoveGenerator.detectMove(Hand.parse("333444")).type()).isEqualTo(CardType.TRIPLE_STRAIGHT);
        assertThat(MoveGenerator.detectMove(Hand.parse("33344456")).type()).isEqualTo(CardType.PLANE_SOLO);
        assertThat(MoveGenerator.detectMove(Hand.parse("3334445566")).type()).isEqualTo(CardType.PLANE_PAIR);
        // 2 不入链
        assertThat(MoveGenerator.detectMove(Hand.parse("JKQA2")).type()).isEqualTo(CardType.WRONG);
        assertThat(MoveGenerator.detectMove(Hand.parse("A2")).type()).isEqualTo(CardType.WRONG);
    }

    @Test
    void leadGenerationCoversAllShapes() {
        Hand hand = Hand.parse("333345566778T");
        List<Hand> moves = MoveGenerator.genAllMoves(hand);
        // 全枚举不含过
        assertThat(moves).noneMatch(Hand::isEmpty);
        // 关键形状都在
        assertThat(moves).contains(Hand.parse("3"));
        assertThat(moves).contains(Hand.parse("33"));
        assertThat(moves).contains(Hand.parse("333"));
        assertThat(moves).contains(Hand.parse("3333"));
        assertThat(moves).contains(Hand.parse("33345"));
        assertThat(moves).contains(Hand.parse("33355"));
        assertThat(moves).contains(Hand.parse("34567"));
        assertThat(moves).contains(Hand.parse("345678"));
        assertThat(moves).contains(Hand.parse("556677"));
    }

    @Test
    void followMustBeat() {
        Hand hand = Hand.parse("334456789");
        // 对 3 可被对 4 / 对 5 压,也有炸弹之外的可压牌
        List<Hand> beats = MoveGenerator.legalMoves(hand, Hand.parse("33"));
        assertThat(beats).contains(Hand.parse("44"));
        assertThat(beats).noneMatch(Hand::isEmpty);
        // 压不过对 K:只有过
        List<Hand> vsK = MoveGenerator.legalMoves(hand, Hand.parse("KK"));
        assertThat(vsK).hasSize(1);
        assertThat(vsK.get(0)).matches(Hand::isEmpty);
        // 有牌可压时绝不过(能管必管)
        List<Hand> vs5 = MoveGenerator.legalMoves(hand, Hand.parse("5"));
        assertThat(vs5).isNotEmpty();
        assertThat(vs5).noneMatch(Hand::isEmpty);
    }

    @Test
    void bombsBeatEverythingNonBomb() {
        Hand hand = Hand.parse("3333445567");
        List<Hand> vsPlane = MoveGenerator.legalMoves(hand, Hand.parse("33344456"));
        assertThat(vsPlane).contains(Hand.parse("3333"));
        List<Hand> vsBombA = MoveGenerator.legalMoves(hand, Hand.parse("AAAA"));
        assertThat(vsBombA).noneMatch(m -> MoveGenerator.detectMove(m).type() == CardType.BOMB);
    }

    @Test
    void pairChainNeedsTwoPairs() {
        Hand hand = Hand.parse("3344555");
        List<Hand> moves = MoveGenerator.genAllMoves(hand);
        assertThat(moves).contains(Hand.parse("3344"));
        // 连对至少 2 对;3345 不是连对
        assertThat(MoveGenerator.detectMove(Hand.parse("3345")).type()).isEqualTo(CardType.WRONG);
    }
}

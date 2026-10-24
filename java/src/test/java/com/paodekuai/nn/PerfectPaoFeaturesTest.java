package com.paodekuai.nn;

import com.paodekuai.game.GameState;
import com.paodekuai.model.Hand;
import com.paodekuai.rules.Deck;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 特征编码的定点检查 */
class PerfectPaoFeaturesTest {

    @Test
    void dimensionsMatchTrainingSide() {
        assertThat(PerfectPaoFeatures.CARD_MAT).isEqualTo(143);
        assertThat(PerfectPaoFeatures.NODE_SIZE).isEqualTo(1188);
        assertThat(PerfectPaoFeatures.SEQ_SIZE).isEqualTo(2145);
        assertThat(PerfectPaoFeatures.ACTION_SIZE).isEqualTo(149);
        assertThat(PerfectPaoFeatures.LSTM_STEPS).isEqualTo(5);
        assertThat(PerfectPaoFeatures.LSTM_IN).isEqualTo(429);
        assertThat(PerfectPaoFeatures.NUM_ACTIONS).isEqualTo(2048);
    }

    @Test
    void cardMatrixRowsArePositional() {
        float[] out = new float[PerfectPaoFeatures.CARD_MAT];
        // A x3 + 炸弹 8 + 单张 2
        PerfectPaoFeatures.cardMatrix(Hand.parse("AAA88882"), out, 0);
        int a = 11, eight = 5, two = 12;
        // 计数行:至少 1/2/3 张点亮,恰 4 张才有第 4 行
        assertThat(out[0 * 13 + a]).isEqualTo(1f);
        assertThat(out[1 * 13 + a]).isEqualTo(1f);
        assertThat(out[2 * 13 + a]).isEqualTo(1f);
        assertThat(out[3 * 13 + a]).isEqualTo(0f);
        assertThat(out[3 * 13 + eight]).isEqualTo(1f);
        // 炸弹行只有 8
        assertThat(out[7 * 13 + eight]).isEqualTo(1f);
        assertThat(out[7 * 13 + a]).isEqualTo(0f);
        // 2 不入链,888 只 3 张不成三顺
        assertThat(out[10 * 13 + two]).isEqualTo(0f);
        assertThat(out[10 * 13 + eight]).isEqualTo(0f);
    }

    @Test
    void encodeFirstDecisionUsesOwnHandAndEmptyHistory() {
        Deck.Deal deal = Deck.deal(new Deck.Rng(42));
        GameState state = new GameState(deal.hands(), deal.leader());
        var view = state.getPublicView(state.getTurn());
        PerfectPaoFeatures.Encoded encoded = PerfectPaoFeatures.encode(view);
        // 首个决策:自己的手牌矩阵等于第 0 段
        Hand myHand = view.getMyHand();
        float[] matrix = new float[PerfectPaoFeatures.CARD_MAT];
        PerfectPaoFeatures.cardMatrix(myHand, matrix, 0);
        for (int i = 0; i < matrix.length; ++i) {
            assertThat(encoded.xImp()[i]).isEqualTo(matrix[i]);
        }
        // 标量段:手上家/下家手数 one-hot 都点亮在 15(16 张 - 1)
        assertThat(encoded.xImp()[PerfectPaoFeatures.NODE_BIN + 15]).isEqualTo(1f);
        assertThat(encoded.xImp()[PerfectPaoFeatures.NODE_BIN + 16 + 15]).isEqualTo(1f);
        // 首手历史为空,seq 全零
        assertThat(encoded.seq()).containsOnly(0f);
    }
}

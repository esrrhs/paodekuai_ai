package com.paodekuai.nn;

import com.paodekuai.game.GameState;
import com.paodekuai.model.Hand;
import com.paodekuai.model.Move;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特征逐位对齐训练侧:重放 golden 的发牌与出牌,对每个决策点比较
 * 候选集合、xImp、seq、动作特征(全部精确相等,无容差)。
 */
class PerfectPaoFeaturesGoldenTest {

    @Test
    void replayMatchesGolden() throws Exception {
        GoldenReader.Golden golden = GoldenReader.load();
        assertTrue(golden.gameCount() >= 24, "golden must hold >=24 games, got " + golden.gameCount());
        assertTrue(golden.gameList().stream().mapToInt(g -> g.decisions().size()).sum() > 400,
                "golden must hold >400 decisions");

        List<String> diffs = new ArrayList<>();
        for (GoldenReader.Game game : golden.gameList()) {
            List<Hand> hands = new ArrayList<>();
            for (String cards : game.deals()) {
                hands.add(Hand.parse(cards));
            }
            GameState state = new GameState(hands, game.lead());
            for (GoldenReader.Decision dec : game.decisions()) {
                assertEquals(dec.turn(), state.getTurn(),
                        "game " + game.index() + " decision turn mismatch");
                assertEquals(dec.hist(), state.getSeq().size(), "history length mismatch");

                var view = state.getPublicView(state.getTurn());
                PerfectPaoFeatures.Encoded encoded = PerfectPaoFeatures.encode(view);
                assertExact(dec.ximp(), encoded.xImp(), diffs,
                        "game " + game.index() + " dec ximp");
                assertExact(dec.seq(), encoded.seq(), diffs,
                        "game " + game.index() + " dec seq");

                List<PerfectPaoFeatures.LegalOption> options = PerfectPaoFeatures.legalOptions(view);
                if (options.size() != dec.optLines().size()) {
                    diffs.add(String.format("game %d: options %d != golden %d",
                            game.index(), options.size(), dec.optLines().size()));
                } else {
                    for (int i = 0; i < options.size(); ++i) {
                        String mine = options.get(i).pattern.toCardString();
                        if (!mine.equals(dec.optLines().get(i))) {
                            diffs.add(String.format("game %d: option %d '%s' != golden '%s'",
                                    game.index(), i, mine, dec.optLines().get(i)));
                        }
                        assertExact(dec.afeat().get(i), options.get(i).feature, diffs,
                                "game " + game.index() + " opt " + i + " afeat");
                    }
                }
                if (dec.chosenSlot() < options.size()) {
                    Move chosen = options.get(dec.chosenSlot()).move;
                    String chosenStr = options.get(dec.chosenSlot()).pattern.toCardString();
                    if (!chosenStr.equals(dec.chosenCards())) {
                        diffs.add(String.format("game %d: chosen '%s' != golden '%s'",
                                game.index(), chosenStr, dec.chosenCards()));
                    }
                    state.step(chosen);
                } else {
                    diffs.add("game " + game.index() + ": chosen slot out of range");
                    break;
                }
            }
        }
        assertTrue(diffs.isEmpty(), () -> "feature mismatches:\n" + String.join("\n", diffs));
    }

    private static void assertExact(float[] expected, float[] actual, List<String> diffs,
                                    String what) {
        if (expected.length != actual.length) {
            diffs.add(what + ": length " + actual.length + " != " + expected.length);
            return;
        }
        for (int i = 0; i < expected.length; ++i) {
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i])) {
                diffs.add(String.format("%s [%d]: %f != %f", what, i, actual[i], expected[i]));
                return;
            }
        }
    }
}

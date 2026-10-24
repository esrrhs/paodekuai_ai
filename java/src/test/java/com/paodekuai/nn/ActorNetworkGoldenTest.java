package com.paodekuai.nn;

import com.paodekuai.ai.NeuralAiPlayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 推理对齐:Java DJL(libtorch 2.7.1)的 logits 对训练侧 libtorch(2.14)
 * 的 golden logits,容差 1e-3,argmax 必须完全一致。
 */
class ActorNetworkGoldenTest {

    private static final float LOGIT_TOLERANCE = 1e-3f;

    @Test
    void logitsMatchGolden() throws Exception {
        ActorNetwork network = ActorNetwork.load(NeuralAiPlayer.defaultModelDir());
        GoldenReader.Golden golden = GoldenReader.load();
        try (network) {
            List<String> diffs = new ArrayList<>();
            double maxDeviation = 0.0;
            int mismatches = 0;
            int decisions = 0;
            for (GoldenReader.Game game : golden.gameList()) {
                for (GoldenReader.Decision dec : game.decisions()) {
                    int n = dec.afeat().size();
                    float[] actionFeat = new float[n * PerfectPaoFeatures.ACTION_SIZE];
                    for (int i = 0; i < n; ++i) {
                        System.arraycopy(dec.afeat().get(i), 0, actionFeat,
                                i * PerfectPaoFeatures.ACTION_SIZE, PerfectPaoFeatures.ACTION_SIZE);
                    }
                    float[] logits = network.score(dec.ximp(), dec.seq(), actionFeat, n);
                    double maxDiff = 0.0;
                    int argmax = 0;
                    float bestVal = Float.NEGATIVE_INFINITY;
                    int goldenArgmax = 0;
                    float goldenBest = Float.NEGATIVE_INFINITY;
                    for (int i = 0; i < n; ++i) {
                        double d = Math.abs(logits[i] - dec.logits()[i]);
                        maxDiff = Math.max(maxDiff, d);
                        if (logits[i] > bestVal) {
                            bestVal = logits[i];
                            argmax = i;
                        }
                        if (dec.logits()[i] > goldenBest) {
                            goldenBest = dec.logits()[i];
                            goldenArgmax = i;
                        }
                    }
                    maxDeviation = Math.max(maxDeviation, maxDiff);
                    if (maxDiff >= LOGIT_TOLERANCE) {
                        diffs.add(String.format("game %d: max logit diff %.6f", game.index(), maxDiff));
                    }
                    if (argmax != goldenArgmax) {
                        ++mismatches;
                        diffs.add(String.format("game %d: argmax %d != golden %d",
                                game.index(), argmax, goldenArgmax));
                    }
                    if (argmax != dec.chosenSlot()) {
                        diffs.add(String.format("game %d: argmax %d != chosen slot %d",
                                game.index(), argmax, dec.chosenSlot()));
                    }
                    ++decisions;
                }
            }
            assertTrue(decisions > 400, "must replay >400 decisions");
            System.out.printf("replayed %d decisions, max logit deviation %.3e, argmax mismatches %d%n",
                    decisions, maxDeviation, mismatches);
            assertTrue(diffs.isEmpty(), () -> "logit mismatches:\n" + String.join("\n", diffs));
            assertEquals(0, mismatches);
        }
    }
}

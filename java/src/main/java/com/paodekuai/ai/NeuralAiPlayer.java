package com.paodekuai.ai;

import com.paodekuai.game.PublicView;
import com.paodekuai.model.Move;
import com.paodekuai.nn.ActorNetwork;
import com.paodekuai.nn.PerfectPaoFeatures;
import com.paodekuai.nn.SharedData;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 策略网络玩家:枚举合法候选 → 编码特征 → 网络打分 → argmax 选牌。
 * 全流程只读 PublicView 公开信息,与训练侧 eval_pimc.cpp 的决策链路一致。
 */
public final class NeuralAiPlayer implements AiPlayer, AutoCloseable {

    public static final String DEFAULT_MODEL_REL = "data/models/ts";

    private final ActorNetwork network;

    public NeuralAiPlayer(ActorNetwork network) {
        this.network = network;
    }

    @Override
    public void close() {
        network.close();
    }

    /** 用默认目录(data/models/ts)加载 */
    public static NeuralAiPlayer create() throws IOException {
        return fromDirectory(SharedData.require(DEFAULT_MODEL_REL));
    }

    public static NeuralAiPlayer fromDirectory(Path dir) throws IOException {
        return new NeuralAiPlayer(ActorNetwork.load(dir));
    }

    public static Path defaultModelDir() throws IOException {
        return SharedData.require(DEFAULT_MODEL_REL);
    }

    @Override
    public AiDecision decide(PublicView view) {
        long start = System.nanoTime();
        if (view.getMyHand().isEmpty()) {
            // 手牌已空不该再决策;防御性返回过
            return new AiDecision(Move.pass(view.getViewingPlayerId()), List.of(), 0);
        }
        PerfectPaoFeatures.Encoded encoded = PerfectPaoFeatures.encode(view);
        List<PerfectPaoFeatures.LegalOption> options = PerfectPaoFeatures.legalOptions(view);
        if (options.isEmpty()) {
            throw new IllegalStateException("no legal option for viewer " + view.getViewingPlayerId());
        }
        if (options.size() == 1) {
            MoveScore only = new MoveScore(options.get(0).move, 1.0);
            return new AiDecision(only.move(), List.of(only), elapsed(start));
        }
        float[] actionFeat = new float[options.size() * PerfectPaoFeatures.ACTION_SIZE];
        for (int i = 0; i < options.size(); ++i) {
            System.arraycopy(options.get(i).feature, 0, actionFeat,
                    i * PerfectPaoFeatures.ACTION_SIZE, PerfectPaoFeatures.ACTION_SIZE);
        }
        float[] logits = network.score(encoded.xImp(), encoded.seq(), actionFeat, options.size());
        int best = 0;
        float bestVal = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < options.size(); ++i) {
            if (logits[i] > bestVal) {
                bestVal = logits[i];
                best = i;
            }
        }
        return new AiDecision(options.get(best).move,
                probabilityTable(options, logits), elapsed(start));
    }

    /** 数值稳定的 softmax,只用于展示;选牌用 logits 直接 argmax */
    private static List<MoveScore> probabilityTable(List<PerfectPaoFeatures.LegalOption> options,
                                                    float[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (float logit : logits) {
            max = Math.max(max, logit);
        }
        double sum = 0.0;
        double[] exp = new double[logits.length];
        for (int i = 0; i < logits.length; ++i) {
            exp[i] = Math.exp(logits[i] - max);
            sum += exp[i];
        }
        List<MoveScore> table = new ArrayList<>(options.size());
        for (int i = 0; i < options.size(); ++i) {
            table.add(new MoveScore(options.get(i).move, exp[i] / sum));
        }
        table.sort((a, b) -> Double.compare(b.probability(), a.probability()));
        return List.copyOf(table);
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}

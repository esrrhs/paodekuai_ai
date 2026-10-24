package com.paodekuai.nn;

import ai.djl.Device;
import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.pytorch.engine.PtNDArray;
import ai.djl.pytorch.engine.PtNDManager;
import ai.djl.pytorch.engine.PtSymbolBlock;
import ai.djl.pytorch.jni.IValue;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 用 DJL 的 libtorch 后端跑重新导出的 TorchScript actor(pao_ts.pt)。
 *
 * <p>训练侧(PerfectPaoCPP)用 {@code torch::serialize::OutputArchive} 存的是纯参数档,
 * 里面没有 forward,任何 TorchScript 运行时都无法直接调用;需要先由本仓库
 * {@code tools/export_actor.py} 重建成带 forward 的脚本模块。脚本用的 PyTorch 版本必须与
 * 这里加载的原生库版本一致(DJL 0.39 对应 libtorch 2.7.1)。
 *
 * <p>训练侧三个座位共享一个模型(相对座位特征),所以这里只有一份权重。
 * 输出是原始 logits,softmax 由调用方按需计算;选动作用 logits 直接 argmax。
 *
 * <p>DJL 依赖在本项目里是 optional 的:不用神经网络玩家时,核心引擎不引入任何原生库。
 */
public final class ActorNetwork implements AutoCloseable {

    public static final String MODEL_FILE = "pao_ts.pt";
    public static final String MODEL_NAME = "pao_ts";

    private final NDManager manager;
    private final Model model;
    private final PtSymbolBlock block;

    private ActorNetwork(NDManager manager, Model model, PtSymbolBlock block) {
        this.manager = manager;
        this.model = model;
        this.block = block;
    }

    /** 从目录加载 pao_ts.pt */
    public static ActorNetwork load(Path dir) throws IOException {
        Path file = dir.resolve(MODEL_FILE);
        if (!Files.isRegularFile(file)) {
            throw new FileNotFoundException("缺少权重文件 " + file
                    + " (需先用 tools/export_actor.py 从训练权重导出 TorchScript)");
        }
        NDManager manager = NDManager.newBaseManager(Device.cpu(), "PyTorch");
        try {
            Model model = Model.newInstance(MODEL_NAME, Device.cpu(), "PyTorch");
            model.load(dir, MODEL_NAME);
            PtSymbolBlock block = (PtSymbolBlock) model.getBlock();
            return new ActorNetwork(manager, model, block);
        } catch (Exception e) {
            manager.close();
            throw new IOException("加载 TorchScript actor 失败: " + e, e);
        }
    }

    /**
     * 给单个决策的全部候选动作打分。
     *
     * @param xImp       1188 维节点特征
     * @param seq        2145 维历史序列([5][429] 展平)
     * @param actionFeat options * 149 维候选动作特征,按槽位顺序紧密排列
     * @param options    候选动作数
     * @return 每个候选动作的得分,长度等于 options
     */
    public synchronized float[] score(float[] xImp, float[] seq,
                                      float[] actionFeat, int options) {
        if (options <= 0) {
            return new float[0];
        }
        long[] sample = new long[options];      // batch=1,每个候选都取自第 0 个状态
        long[] slot = new long[options];
        for (int i = 0; i < options; ++i) {
            slot[i] = i;
        }
        try (NDManager sub = manager.newSubManager()) {
            PtNDManager pt = (PtNDManager) sub;
            NDArray x = sub.create(xImp, new Shape(1, PerfectPaoFeatures.NODE_SIZE));
            NDArray s = sub.create(seq, new Shape(1, PerfectPaoFeatures.LSTM_STEPS,
                    PerfectPaoFeatures.LSTM_IN));
            NDArray a = sub.create(actionFeat, new Shape(options, PerfectPaoFeatures.ACTION_SIZE));
            NDArray ids = sub.create(slot, new Shape(options));
            NDArray samples = sub.create(sample, new Shape(options));
            // IValue 是独立的原生对象,不随 NDManager 释放,必须逐个 close
            IValue[] inputs = new IValue[]{
                    IValue.from((PtNDArray) x),
                    IValue.from((PtNDArray) s),
                    IValue.from((PtNDArray) a),
                    IValue.from((PtNDArray) samples),
                    IValue.from((PtNDArray) ids)};
            try {
                IValue result = block.forward(inputs);
                try (PtNDArray logits = result.toTensor(pt)) {
                    // 未使用的槽位被填成 -1e9,前 options 个就是各候选的得分
                    return Arrays.copyOf(logits.toFloatArray(), options);
                } finally {
                    result.close();
                }
            } finally {
                for (IValue input : inputs) {
                    input.close();
                }
            }
        }
    }

    @Override
    public void close() {
        model.close();
        manager.close();
    }

    /** 判断 DJL 与 PyTorch 引擎是否可用(依赖是 optional 的,缺失时不应连累核心引擎) */
    public static boolean isAvailable() {
        try {
            return ai.djl.engine.Engine.hasEngine("PyTorch");
        } catch (Throwable t) {
            return false;
        }
    }
}

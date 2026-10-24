# tools — 权重重导出与基准生成

两个工具都**不属于运行时**:服务运行只依赖产物 `data/models/ts/` 与
`data/golden/golden24.txt.gz`。

## export_actor.py

PerfectPaoCPP 的 `torch_nn::ActorImpl::save` 用 libtorch `OutputArchive`
只写参数不写 forward,任何 TorchScript 运行时(包括 DJL)都无法直接调用。
两段式重导出:

```bash
# 1) extract:用训练侧同版本 torch(PerfectPaoCPP 训练用的是 2.14.x)读参数
python3 tools/export_actor.py extract --in data/models/latest --out /tmp/params

# 2) script:用与原生库匹配的 torch 重建模型并 torch.jit.script
#    (Java DJL 0.39 配套 libtorch 2.7.1;PyTorch 不保证新版序列化能被旧运行时读)
python3 tools/export_actor.py script --in /tmp/params --out data/models/ts
```

产物:`pao_ts.pt`(TorchScript,Java 用)+ `pao_manifest.json`
(Go/C++ 用)。script 步骤会用 `torch.equal` 逐层校验 zip 条目
`<root>/data/<i>` 与 `named_parameters()[i]` 一致。

## TorchScript zip 结构(供 Go/C++ 加载方)

- 张量数据全部是 **STORED(不压缩)** 条目,名为 `<root>/data/<i>`,
  i 按模块注册顺序编号;
- `pao_manifest.json` 记录 `root / dtype / byteOrder / gateOrder / parameters`,
  parameters[i] = `{entry, name, shape}`;
- 14 个参数:`lstm.weight_ih_l0[512,429]`、`lstm.weight_hh_l0[512,128]`、
  `lstm.bias_ih_l0[512]`、`lstm.bias_hh_l0[512]`、`l1.weight[256,1465]`、
  `l1.bias[256]`、`l2/l3.weight[256,256]` 及 bias、`l4.weight[512,256]`、
  `l4.bias[512]`、`head.weight[1,512]`、`head.bias[1]`;
- **门序陷阱**:四块 LSTM 权重按 PyTorch 约定依次是 i, f, g, o
  (g 是候选 cell 值),不是 cuDNN 的 i, h, f, c。写错则 logits 漂移到
  千位量级、大量 argmax 错位。manifest 的 `gateOrder` 字段就是给
  C++/Go 加载方做防呆校验的。

## pddump.cpp — golden 基准导出

链接 PerfectPaoCPP 训练侧与它的 libtorch,用策略网络把整局跑完,把每个
决策点的特征、候选、libtorch logits 与选牌全部倒出来:

```bash
TORCH=<PerfectPaoCPP 构建用的 torch>/lib/python3.13/site-packages/torch
clang++ -std=c++20 -O2 -o /tmp/pddump tools/pddump.cpp \
    -I<PerfectPaoCPP>/src \
    -I$TORCH/include -I$TORCH/include/torch/csrc/api/include \
    <PerfectPaoCPP>/build/libperfectpao.a \
    -L$TORCH/lib -ltorch -ltorch_cpu -lc10 -lpthread -ldl

# 24 局、种子 777(与仓库里的 data/golden/golden24.txt.gz 相同参数)
DYLD_LIBRARY_PATH=$TORCH/lib /tmp/pddump 24 777 data/models/latest > golden.txt
gzip -c golden.txt > data/golden/golden24.txt.gz
```

## golden 记录格式

token 流,数值 " %.9g",每行最多 20 个;`-` 表示过牌:

```
PAOGOLDEN 1 <games> <seed0>
GAME <g>
DEAL <seat> <cards>       ×3
LEAD <seat>               首出座位
DEC <d> TURN <seat> HIST <历史手数>
XIMP 1188   <浮点>        节点特征(8 静态矩阵 + 44 标量)
SEQ 2145    <浮点>        15 个历史矩阵展平
OPT <L>
OPTLINE <cards>           ×L,候选顺序("" 过牌写成 -)
AFEAT 149   <浮点>        每个候选的动作特征
LOGIT <L>   <浮点>        训练侧 libtorch logits(按候选槽位)
CHOSEN <slot> <cards>
ACT <seat> <cards>        该步实际出的牌,读者可增量回放
ENDDEC
... ENDGAME
```

对齐口径:候选集合精确相等;xImp/seq/afeat 逐位相等;logits
`max|Δ| < 1e-3`;argmax 零错位。当前基准:24 局 549 决策。

# 跑得快 AI (paodekuai_ai)

[![License](https://img.shields.io/github/license/esrrhs/paodekuai_ai)](https://github.com/esrrhs/paodekuai_ai)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.esrrhs/paodekuai-ai)](https://central.sonatype.com/artifact/com.github.esrrhs/paodekuai-ai)
[![Java CI](https://github.com/esrrhs/paodekuai_ai/actions/workflows/maven.yml/badge.svg?branch=master)](https://github.com/esrrhs/paodekuai_ai/actions/workflows/maven.yml)
[![Go CI](https://github.com/esrrhs/paodekuai_ai/actions/workflows/go.yml/badge.svg?branch=master)](https://github.com/esrrhs/paodekuai_ai/actions/workflows/go.yml)
[![C++ CI](https://github.com/esrrhs/paodekuai_ai/actions/workflows/cpp.yml/badge.svg?branch=master)](https://github.com/esrrhs/paodekuai_ai/actions/workflows/cpp.yml)
[![Server CI](https://github.com/esrrhs/paodekuai_ai/actions/workflows/server.yml/badge.svg?branch=master)](https://github.com/esrrhs/paodekuai_ai/actions/workflows/server.yml)

*English: [README.md](README.md)*

三套独立实现的跑得快 AI 引擎(Java 17 / Go 1.21 / C++20),外加一个基于
[FakeLua](https://github.com/esrrhs/fakelua) 的**可玩网页客户端**。出牌由
强化学习策略网络驱动,权重来自 [PerfectPaoCPP](https://github.com/esrrhs/PerfectPaoCPP)
的训练(PerfectDou 式自博弈 PPO,权重随本仓库分发)。三种语言共享同一份权重与
同一份 golden 基准,在 549 个真实决策上 logits 与选牌完全一致。

---

## 📂 目录结构

```text
paodekuai_ai/
├── java/            # Java 17,Maven 坐标 com.github.esrrhs:paodekuai-ai
│   └── src/main/java/com/paodekuai/{model,rules,game,ai,nn}/  + Main.java(三 AI 自对弈演示)
├── go/              # Go 1.21,零第三方依赖(手写前向传播,无 cgo/ONNX)
├── cpp/             # C++20,零第三方依赖(自解析 zip + 手写前向传播)
├── server/          # 网页平台:Lua 业务逻辑 + C++ 宿主 + 前端
│   ├── lua/  host/  static/  tests/  API.md  README.md
├── data/
│   ├── models/latest/   # 训练侧原始输出(只有参数,没有 forward)
│   ├── models/ts/       # 可运行的 TorchScript 权重 + 逐层 manifest(共享,3.7 MB)
│   └── golden/          # 训练侧导出的逐决策基准(共享)
└── tools/           # 权重重导出与基准生成(不属于运行时)
```

规则、牌局状态机与特征编码在各语言里独立实现;只有**权重**与 **golden 基准**
是共享的。

---

## 🎯 "对齐"的确切含义

对齐是写进测试的契约,不是感觉。`data/golden/golden24.txt.gz` 收录 24 局、
549 个决策,由训练侧 `tools/pddump.cpp` 导出:

| 校验项 | 要求 |
|---|---|
| 候选集合 | 与训练侧完全一致(按 13 点数多重集比较,含每个具体的飞机带翅组合) |
| `xImp` / `seq` / 动作特征 | **逐位相等**,无容差 |
| logits | 最大绝对偏差 < 1e-3 |
| argmax 选牌 | 必须完全一致 |

各语言自己的测试实测:

| 实现 | 最大 logits 偏差 | argmax 错位 |
|---|---|---|
| Java(DJL 加载 libtorch) | 9.155e-5 | 0 |
| Go(手写前向) | 1.678e-4 | 0 |
| C++(手写前向) | 1.526e-4 | 0 |

残差只来自浮点求和顺序,因此 C++ 构建强制 `-ffp-contract=off`,容差从未放宽。
`server/` 复用 C++ 引擎,所以网页服务的出牌与 `cpp/` 逐位一致。

---

## 🚀 各实现的使用方式

### Java

```bash
cd java
./mvnw clean test                    # 含与训练侧的逐位对齐测试
./mvnw exec:java                     # 命令行演示:三个 AI 打一局
./mvnw exec:java -Dexec.args="--model-dir=/path/to/weights --games 10"
```

```xml
<dependency>
    <groupId>com.github.esrrhs</groupId>
    <artifactId>paodekuai-ai</artifactId>
    <version>2.0.0</version>
</dependency>
```

规则引擎(`model` / `rules` / `game`)无依赖。出牌 AI 需要原生 libtorch,
DJL 依赖在 pom 里标了 `optional`——不会传递给使用方;要用 `NeuralAiPlayer`
需自行加入引擎与所在平台的原生库:

```xml
<dependency>
    <groupId>ai.djl.pytorch</groupId>
    <artifactId>pytorch-engine</artifactId>
    <version>0.39.0</version>
</dependency>
<!-- 显式固定原生库,否则 DJL 首次使用时会下载到 ~/.djl.ai -->
<dependency>
    <groupId>ai.djl.pytorch</groupId>
    <artifactId>pytorch-native-cpu</artifactId>
    <classifier>osx-aarch64</classifier>
    <version>2.7.1</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>ai.djl.pytorch</groupId>
    <artifactId>pytorch-jni</artifactId>
    <version>2.7.1-0.39.0</version>
    <scope>runtime</scope>
</dependency>
```

权重必须用与加载方一致的 libtorch 主版本导出(本项目固定 **2.7.1**,
DJL 0.39 配套的最新原生库)。

### Go

```bash
cd go && go test ./...      # 零第三方依赖
```

包名 `github.com/esrrhs/paodekuai_ai/go`;`Load()` 读取共享的
`data/models/ts` 权重,`Decide` 返回选中的出牌与全部候选 logits。

### C++

```bash
cd cpp
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
ctest --test-dir build --output-on-failure
```

```cpp
#include <cstdio>

#include "paodekuai/api.h"

int main()
{
    paodekuai::Policy policy;
    if (!policy.Load(paodekuai::Policy::DefaultModelDir()))
    {
        std::fprintf(stderr, "%s\n", policy.Error().c_str());
        return 1;
    }
    std::array<paodekuai::CardSet, 3> hands{
        paodekuai::CardSet::parse("33445566778899TTJQKA"),
        paodekuai::CardSet::parse("34556778899TTJQKA2"),
        paodekuai::CardSet::parse("3344666778899TKKQA")};
    paodekuai::GameState state;
    state.hand = hands;
    state.turn = 0;
    state.lastPlayer = 0;
    const auto d = policy.Decide(state.getPublicView(state.turn));
    std::printf("%s (%d candidates)\n", d.move.str().c_str(),
                static_cast<int>(d.options.size()));
    return 0;
}
```

---

## 🌐 网页平台(FakeLua)

一个真人(座位 0)对阵两个策略网络 AI:首出指定、AI 提示、AI 思考面板、
记牌器、战报日志、按会话隔离。

```bash
cd server
cmake -S host -B host/build -DCMAKE_BUILD_TYPE=Release   # 需先安装 fakelua,见 server/README.md
cmake --build host/build -j
PAODEKUAI_PORT=8080 ./host/build/paodekuai_server lua     # http://localhost:8080
python3 tests/api_test.py                                 # HTTP 契约用例
```

规则与推理都留在 C++;Lua 只负责 HTTP、会话与流程。契约见
[server/API.md](server/API.md)。

---

## 🧠 权重与重导出

`data/models/latest` 是训练机通过 libtorch `OutputArchive` 写出的
(PerfectPaoCPP `torch_nn::ActorImpl::save`):**只有参数、没有 forward**,
任何 TorchScript 运行时都无法直接调用,必须重建:

```bash
# 1) 用训练侧同版本 PyTorch 提取参数
python3 tools/export_actor.py extract --in data/models/latest --out /tmp/params
# 2) 用与原生库匹配的版本重建并脚本化(本项目 2.7.1)
python3 tools/export_actor.py script --in /tmp/params --out data/models/ts
```

`export_actor.py` 同时写出 `pao_manifest.json`。TorchScript zip 把张量存成
**STORED(不压缩)** 条目,manifest 记录 "条目序号 → 层 → 形状",Go/C++ 只用
标准库 zip 读取器即可加载权重。golden 基准来自 `tools/pddump.cpp`。
步骤、记录格式与陷阱(LSTM 门块顺序为 **i, f, g, o**;写错偏差会到千位)见
[tools/README.md](tools/README.md)。

---

## 🃏 规则

48 张无花色牌(3..K 各 4 张、A 3 张、2 1 张);三人对称,各持 16 张,无底牌。
场上 4 张 3 中随机指定一张,其持有者先出,第一手必须包含 3。支持牌型:单张、
对子、三张、三带二(带一对或两单)、顺子(≥5)、连对(≥2)、三顺(≥2)、
飞机带单/带对、炸弹。无王、无王炸、无四带二。**能管必管**:应牌时只有无牌可压
才允许过(炸弹在必管集合里)。**报单**:下家剩 1 张时,自由出牌出单张必须顶手中
最大单张。一人出完即终局,赢家收两家剩牌数(全关翻倍)。

策略网络只看公开信息(自己的手牌、两家合计的未知牌池、各家已出与各家最近一手、
最近 15 手、相对座位的各家手数、炸弹数、报单标志),**绝不读对手私有手牌**。
界面上的数字是 softmax **策略概率**(模型偏好),不是搜索胜率。

---

## 📝 关于旧引擎

原来的出牌引擎是 **PIMC 采样 + Max^N MCTS 搜索**,带牌型启发、过牌推断与
炸弹护栏。与 [doudizhu_ai](https://github.com/esrrhs/doudizhu_ai) 用策略网络
替换自家搜索引擎一样,本仓库 2.0.0 起整条搜索链(`PimcAiPlayer`、
`MctsSearcher`、`Determinizer`、`FastRolloutPolicy`、`BombPolicy`、
`PassInference`)全部移除,网页客户端从内嵌 Java 服务迁移到 `server/`
(FakeLua + C++ 宿主)。2.0.0 之前的提交仍保留旧实现。

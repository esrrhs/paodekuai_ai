# 跑得快 AI (PaoDeKuai AI - 现代版)

基于 **Java 17** 开发的高性能扑克牌游戏“**跑得快**”人工智能引擎，采用**完美信息蒙特卡洛算法 (PIMC, Perfect Information Monte Carlo)** 配合 **Max^N 蒙特卡洛树搜索 (MCTS)**，针对不完全信息博弈实现严谨且强力的出牌决策。

---

## 🌟 核心特性与架构升级

本项目在保留跑得快标准规则的基础上，彻底重构并刷新了工程体系与算法内核：

### 1. 彻底解决原算法的缺陷
- **非全知视角 (不完全信息)**：严格隐藏对手手牌，通过 `PublicView` 仅感知自身手牌、对手剩余牌数、已出牌历史和桌面压牌。
- **PIMC 确定化采样**：决策时基于未知牌池生成 $K$ 个假想的对手手牌世界（Determinization），并在各个采样世界中独立推演。
- **标准的 Max^N MCTS 四阶段**：
  - **Selection (选择)**：基于三方收益向量与 UCB1 公式在树中下潜。
  - **Expansion (扩展)**：对尚未探索的合法出牌动作新建子节点。
  - **Simulation / Rollout (快速推演)**：脱离搜索树，使用轻量启发式策略快速对局至终局，彻底解决了原项目缺失 Rollout 导致的伪 MCTS 偏斜深搜问题。
  - **Backpropagation (反向传播)**：将终局胜负向量沿路径准确回传，修复了原项目整除截断导致胜率恒为 0 以及终局节点访问计数为 0 的严重 Bug。

### 2. 现代化 Java 17 工程体系
- **标准 Maven 结构**：遵循 Maven 标准目录结构，支持单元测试、依赖管理与一键打包。
- **优雅的领域模型**：
  - `com.paodekuai.model`: `Rank`、`CardType`、`Move`、`Hand` 等核心领域对象，具备不可变性与完备的哈希比较逻辑。
  - `com.paodekuai.rules`: `Deck`、`MoveGenerator`（覆盖单张、对子、顺子、连对、三带、四带二、飞机、炸弹等全部跑得快牌型及“能管必管”规则）。
  - `com.paodekuai.game`: `GameState`（状态机、轮次与桌面状态结算）、`PublicView`。
  - `com.paodekuai.ai`: `Determinizer`、`MctsNode`、`MctsSearcher`、`FastRolloutPolicy`、`PimcAiPlayer`。
- **自动化测试**：基于 JUnit 5 与 AssertJ 构建针对牌型生成、压牌比对、未知牌守恒性与 AI 必胜态收敛的单元测试。

---

## 🚀 快速开始

### 环境依赖
- JDK 17+
- Apache Maven 3.5+

### 1. 编译并运行单元测试
```bash
mvn clean test
```

### 2. 运行 AI 对局模拟演示
```bash
mvn exec:java
```

或者打包成独立 JAR 后执行：
```bash
mvn package
java -jar target/paodekuai-ai-2.0.0.jar
```

---

## 🎮 控制台演示效果

对局过程中，AI 将清晰打印其**候选动作空间**、**在多个可能世界中的总采样与访问分布**、**预估平均胜率**及**决策耗时**（单步决策平均仅需 10~100ms）：

```text
----------- [第 37 步] 轮到玩家 P0 出牌 (手牌剩 3 张) -----------
当前手牌: 3,7,Q
桌面状态: [自由主动出牌]
  AI 评估候选动作数: 3，耗时: 9 ms
    ★ 3 -> 总访问: 1800, 平均胜率: 100.0% (采样: 25次)
      7 -> 总访问: 1800, 平均胜率: 100.0% (采样: 25次)
      Q -> 总访问:  150, 平均胜率:   0.0% (采样: 25次)
>>> 玩家 P0 决定出牌: 3
```

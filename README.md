# 跑得快 AI (PaoDeKuai AI)

[![License](https://img.shields.io/github/license/esrrhs/paodekuai_ai)](https://github.com/esrrhs/paodekuai_ai)
[![Language](https://img.shields.io/github/languages/top/esrrhs/paodekuai_ai)](https://github.com/esrrhs/paodekuai_ai)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.esrrhs/paodekuai-ai)](https://central.sonatype.com/artifact/com.github.esrrhs/paodekuai-ai)
[![Build Status](https://github.com/esrrhs/paodekuai_ai/actions/workflows/maven.yml/badge.svg?branch=master)](https://github.com/esrrhs/paodekuai_ai/actions)

基于 **Java 17** 实现的高性能扑克牌游戏“**跑得快**”人工智能引擎与本地网页端对战平台。

核心算法采用**完美信息蒙特卡洛 (PIMC, Perfect Information Monte Carlo)** 结合 **Max^N 蒙特卡洛树搜索 (MCTS)**，针对跑得快这一典型不完全信息博弈实现严谨且强力的出牌决策。

---

## 📦 依赖引入 (Installation)

### Maven
在项目的 `pom.xml` 中添加依赖：
```xml
<dependency>
    <groupId>com.github.esrrhs</groupId>
    <artifactId>paodekuai-ai</artifactId>
    <version>1.0.1</version>
</dependency>
```

### Gradle
```groovy
implementation 'com.github.esrrhs:paodekuai-ai:1.0.1'
```

---

## 🌟 核心特性

### 1. PIMC + Max^N MCTS 决策算法
- **不完全信息隐藏与确定化采样 (Determinization)**：玩家视角严格受限，仅感知自身手牌、对手剩余牌数与出牌历史。AI 决策时通过未知牌池采样生成多个完全信息假想世界分别推演。
- **标准的 Max^N MCTS 四阶段**：
  - **Selection (选择)**：基于多人博弈 UCB1 公式在搜索树中下潜；
  - **Expansion (扩展)**：对未探索动作建树；
  - **Simulation / Rollout (快速推演)**：使用轻量启发式规则快速模拟至终局；
  - **Backpropagation (反向传播)**：三方胜负收益向量沿树回传更新。
- **多世界聚合评估**：跨采样世界汇总各合法出牌动作的访问频次与胜率期望，选取最优稳健着法。

### 2. 网页端交互对战平台 (1 真人 vs 2 AI)
- **仿真绿色扑克桌**：实时渲染手牌、出牌气泡、剩余牌量与出牌倒计时/轮次指示。
- **💡 AI 智能提示**：遇到疑难牌局时，一键获取 PIMC AI 的推荐着法并自动高亮手牌。
- **🔍 实时 AI 思考雷达**：可视化展示 AI 在各可能世界采样中的候选动作分布、访问量与预估胜率柱状图。
- **📊 记牌器与对局日志**：实时统计全场剩余牌张分布（3 ~ 2）并记录完整出牌历史。

### 3. 规范的跑得快规则引擎
- 支持三人 48 张标准玩法（每人 16 张，无大小王，去 3 张 2 和 1 张 A）。
- 完整支持所有常见牌型：单张、对子、三张、三带一、三带二、顺子、连对、飞机（不带/带单/带对）、四带二、炸弹。
- 严格遵循跑得快“能管必管”规则（有牌能压时不可过牌）。

---

## 🚀 快速开始

### 环境依赖
- JDK 17+
- Apache Maven 3.5+

### 1. 启动网页端对战平台 (推荐)
执行以下命令启动本地 Web 服务：
```bash
mvn exec:java
```
服务启动后，在浏览器中访问：
👉 **http://localhost:8080**

*(如需指定端口，可执行：`mvn exec:java -Dexec.args="--port=8088"`)*

### 2. 运行命令行 AI 对局模拟 (Benchmark)
若需在终端中查看 3 个 AI 纯自动对局的深度思考过程与结算：
```bash
mvn exec:java -Dexec.args="--cli"
```

### 3. 运行自动化单元测试
```bash
mvn clean test
```

---

## 📂 项目结构

```text
paodekuai_ai/
├── pom.xml                                   # Maven 配置 (Java 17, JUnit 5, AssertJ, Jackson, Logback)
├── src/
│   ├── main/
│   │   ├── java/com/paodekuai/
│   │   │   ├── model/                        # 领域模型 (Rank, CardType, Move, Hand)
│   │   │   ├── rules/                        # 规则与牌库 (Deck, MoveGenerator)
│   │   │   ├── game/                         # 牌局状态机与信息视角 (GameState, Player, PublicView)
│   │   │   ├── ai/                           # PIMC 与 MCTS 核心 (Determinizer, MctsSearcher, PimcAiPlayer)
│   │   │   ├── web/                          # Web 会话与 HTTP 服务 (GameSession, GameHttpServer)
│   │   │   └── Main.java                     # 统一启动入口
│   │   └── resources/
│   │       ├── logback.xml                   # 日志配置
│   │       └── static/                       # Web 前端资源 (index.html, style.css, app.js)
│   └── test/                                 # 单元测试 (MoveGeneratorTest, PimcAITest)
```

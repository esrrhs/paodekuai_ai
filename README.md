# Pao De Kuai AI (paodekuai_ai)

[![License](https://img.shields.io/github/license/esrrhs/paodekuai_ai)](https://github.com/esrrhs/paodekuai_ai)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.esrrhs/paodekuai-ai)](https://central.sonatype.com/artifact/com.github.esrrhs/paodekuai-ai)
[![Java CI](https://github.com/esrrhs/paodekuai_ai/actions/workflows/maven.yml/badge.svg?branch=master)](https://github.com/esrrhs/paodekuai_ai/actions/workflows/maven.yml)
[![Go CI](https://github.com/esrrhs/paodekuai_ai/actions/workflows/go.yml/badge.svg?branch=master)](https://github.com/esrrhs/paodekuai_ai/actions/workflows/go.yml)
[![C++ CI](https://github.com/esrrhs/paodekuai_ai/actions/workflows/cpp.yml/badge.svg?branch=master)](https://github.com/esrrhs/paodekuai_ai/actions/workflows/cpp.yml)
[![Server CI](https://github.com/esrrhs/paodekuai_ai/actions/workflows/server.yml/badge.svg?branch=master)](https://github.com/esrrhs/paodekuai_ai/actions/workflows/server.yml)

*中文版: [README_CN.md](README_CN.md)*

Three independent implementations of a Pao De Kuai (Run Fast, 跑得快) AI engine
(Java 17 / Go 1.21 / C++20) plus a **playable web client** built on
[FakeLua](https://github.com/esrrhs/fakelua). Card play is driven by a
reinforcement-learning policy network trained with
[PerfectPaoCPP](https://github.com/esrrhs/PerfectPaoCPP) (PerfectDou-style
self-play PPO; weights checked into this repo). All three languages share one
set of weights and one golden fixture and agree on the logits and the chosen
move across 549 real decisions.

---

## 📂 Layout

```text
paodekuai_ai/
├── java/            # Java 17, Maven coordinate com.github.esrrhs:paodekuai-ai
│   └── src/main/java/com/paodekuai/{model,rules,game,ai,nn}/  + Main.java (3-AI self-play demo)
├── go/              # Go 1.21, zero third-party deps (hand-written forward pass, no cgo/ONNX)
├── cpp/             # C++20, zero third-party deps (own zip parsing + hand-written forward pass)
├── server/          # web platform: Lua business logic + C++ host + frontend
│   ├── lua/  host/  static/  tests/  API.md  README.md
├── data/
│   ├── models/latest/   # raw training output (parameters only, no forward)
│   ├── models/ts/       # runnable TorchScript weights + per-layer manifest (shared, 3.7 MB)
│   └── golden/          # per-decision fixture exported from the training side (shared)
└── tools/           # weight re-export and fixture generation (not part of runtime)
```

Rules, game state machine and feature encoding are implemented separately in
each language; only the **weights** and the **golden fixture** are shared.

---

## 🎯 What "parity" means here

Parity is a contract written into the tests, not a vibe. The fixture
`data/golden/golden24.txt.gz` holds 24 games and 549 decisions dumped from the
training side by `tools/pddump.cpp`:

| Checked | Requirement |
|---|---|
| Candidate set | identical to the training side (compared as 13-rank multisets, including every concrete plane-wing combination) |
| `xImp` / `seq` / action features | **bit-identical**, no tolerance |
| logits | max absolute deviation < 1e-3 |
| argmax move | must match exactly |

Measured by each language's own test run:

| Implementation | Max logit deviation | argmax mismatches |
|---|---|---|
| Java (libtorch via DJL) | 9.155e-5 | 0 |
| Go (hand-written forward) | 1.678e-4 | 0 |
| C++ (hand-written forward) | 1.526e-4 | 0 |

The residual comes only from floating-point summation order, so the C++ build
forces `-ffp-contract=off` and the tolerance has never been loosened.
`server/` reuses the C++ engine, so the served moves are bit-identical to
`cpp/`.

---

## 🚀 Using each implementation

### Java

```bash
cd java
./mvnw clean test                    # includes bit-level parity with the training side
./mvnw exec:java                     # command-line demo: three AIs play one game
./mvnw exec:java -Dexec.args="--model-dir=/path/to/weights --games 10"
```

```xml
<dependency>
    <groupId>com.github.esrrhs</groupId>
    <artifactId>paodekuai-ai</artifactId>
    <version>2.0.0</version>
</dependency>
```

The rules engine (`model` / `rules` / `game`) has no dependencies. The play AI
needs native libtorch, and the DJL dependencies are marked `optional` in the
pom — they do **not** propagate to consumers — so using `NeuralAiPlayer` means
adding the engine and your platform's native library yourself:

```xml
<dependency>
    <groupId>ai.djl.pytorch</groupId>
    <artifactId>pytorch-engine</artifactId>
    <version>0.39.0</version>
</dependency>
<!-- pin the native library explicitly, otherwise DJL downloads it into ~/.djl.ai on first use -->
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

Weights must be exported with the same libtorch major version that loads them
(this project is pinned to **2.7.1**, the newest native library in DJL 0.39).

### Go

```bash
cd go && go test ./...      # zero third-party dependencies
```

The package is `github.com/esrrhs/paodekuai_ai/go`; `Load()` reads the shared
`data/models/ts` weights, `Decide` returns the chosen move plus every
candidate's logit. See the package tests for a full game replay.

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

## 🌐 Web platform (FakeLua)

One human (seat 0) versus two policy-network AIs: first-lead designation, move
hints, an AI reasoning panel, a card counter, a game log, and per-session
isolation.

```bash
cd server
cmake -S host -B host/build -DCMAKE_BUILD_TYPE=Release   # needs fakelua installed, see server/README.md
cmake --build host/build -j
PAODEKUAI_PORT=8080 ./host/build/paodekuai_server lua     # http://localhost:8080
python3 tests/api_test.py                                 # HTTP contract cases
```

Rules and inference stay in C++; Lua only handles HTTP, sessions and flow.
The contract is documented in [server/API.md](server/API.md).

---

## 🧠 Weights and re-export

`data/models/latest` is what the training machine writes through a libtorch
`OutputArchive` (PerfectPaoCPP `torch_nn::ActorImpl::save`): **parameters
only, no forward**, so no TorchScript runtime can call it directly. It has to
be rebuilt:

```bash
# 1) extract the parameters with the same PyTorch version the training side used
python3 tools/export_actor.py extract --in data/models/latest --out /tmp/params
# 2) rebuild and script with the version that matches the native library (2.7.1 here)
python3 tools/export_actor.py script --in /tmp/params --out data/models/ts
```

`export_actor.py` also writes `pao_manifest.json`. A TorchScript zip stores
tensors as **STORED (uncompressed)** entries, so the manifest maps
"entry index → layer → shape" and Go/C++ can load the weights with nothing but
a standard-library zip reader. The fixture comes from `tools/pddump.cpp`.
Steps, record format and the traps (the LSTM gate chunks are ordered
**i, f, g, o**; getting it wrong produces deviations in the thousands) are in
[tools/README.md](tools/README.md).

---

## 🃏 Rules

48 suit-less cards (3..K ×4, A ×3, 2 ×1); three symmetric players hold 16
cards each, no kitty. One of the four 3s is designated at random and its
holder leads; the first hand must contain a 3. Supported shapes: single, pair,
triple, triple-plus-two loose kickers, straight (5+), pair chain (2+), bare
triple chain (2+), airplane with solo or pair wings, bomb. No jokers, no
rocket, no four-plus-two. **Must-beat**: when following, pass is legal only if
nothing beats the table (bombs included). **Report-single**: when leading a
solo while the next player holds exactly one card, the lead must top with the
largest solo. One player emptying their hand ends the game; the winner collects
the losers' remaining-card counts (doubled for a 16-card shutdown).

The policy network sees public information only (own hand, the combined
unknown cards of the other two, what each player has played and each player's
last move, the last 15 moves, relative-seat hand counts, bomb count, report
flags) and **never reads another player's private hand**. The numbers in the
UI are softmax **policy probabilities** (model preference), not search win
rates.

---

## 📝 Notes on the previous engine

The original play engine was **PIMC sampling plus a Max^N MCTS search** with
hand-shape heuristics, pass inference and bomb-policy guardrails. The policy
network replaces the whole search chain (`PimcAiPlayer`, `MctsSearcher`,
`Determinizer`, `FastRolloutPolicy`, `BombPolicy`, `PassInference`) the same
way [doudizhu_ai](https://github.com/esrrhs/doudizhu_ai) replaced its own
search engine, and the web client moved from an embedded Java server to
`server/` (FakeLua + C++ host). Everything before the 2.0.0 rewrite still
contains that implementation.

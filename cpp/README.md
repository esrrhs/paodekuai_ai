# paodekuai_ai — C++

Zero-third-party-dependency C++20 implementation: the rules engine, the
feature encoding, a hand-written actor forward pass over the shared TorchScript
weights (own zip central-directory parser, no libtorch, no zlib in the library)
and the golden parity test against `data/golden/golden24.txt.gz`.

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
ctest --test-dir build --output-on-failure
```

- `include/paodekuai/api.h` — umbrella header
- `paodekuai::Policy` — weight loading + scoring + `Decide(PublicView)`
- `paodekuai::GameState` — Hunan 3-player 16-card rules (must-beat,
  first-hand-3, report-single)
- tests are run with the working directory set to `cpp/` so the shared
  `data/` directory (one level up) is found by walking up parents

`-ffp-contract=off` is forced so the hand-written float32 forward stays
bit-comparable with the Java/Go implementations.

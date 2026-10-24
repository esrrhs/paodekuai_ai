# paodekuai_ai — Go

Zero-dependency Go implementation (no cgo, no ONNX): the rules engine, the
feature encoding and a hand-written actor forward pass that loads the shared
TorchScript weights with nothing but `archive/zip` + `encoding/json`
(STORED entries + `pao_manifest.json`).

```bash
go test ./...     # includes golden parity: features bit-exact, logits < 1e-3, argmax exact
```

Package `github.com/esrrhs/paodekuai_ai/go`; `Load()` reads
`data/models/ts` (walking up parents from the working directory), `Decide`
returns the chosen move and per-candidate logits.

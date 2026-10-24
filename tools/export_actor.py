#!/usr/bin/env python3
"""Re-exports the PerfectPaoCPP actor checkpoint for every runtime.

The training side (PerfectPaoCPP ``torch_nn::ActorImpl::save``) writes a
libtorch ``OutputArchive``: parameters only, no forward, so no TorchScript
runtime can call it directly. This tool rebuilds the module and scripts it:

  extract  read ``data/models/latest/actor.pt`` with the torch version the
           training side used and dump the raw parameter tensors
  script   rebuild the actor with the torch version that matches the runtime
           (Java DJL pins libtorch 2.7.1), ``torch.jit.script`` it, and write
           ``pao_ts.pt`` plus ``pao_manifest.json``

The TorchScript zip stores tensor data as STORED (uncompressed) entries named
``<root>/data/<i>``, numbered in module registration order, so the manifest
maps "entry index -> layer -> shape" and Go/C++ can load the weights with
nothing but a standard-library zip reader.
"""

import argparse
import json
import os
import sys
import zipfile

import torch

LSTM_IN = 429          # 3 moves x 143 per LSTM step
LSTM_HIDDEN = 128
LSTM_STEPS = 5
NODE_IN = 1188         # 8 static matrices (1144) + 44 scalars
ACTION_IN = 149        # 143 matrix + 6 scalars
HIDDEN = 256
NUM_ACTIONS = 2048

LSTM_GATE_ORDER = (
    "LSTM weight chunks are ordered i (input gate), f (forget gate), "
    "g (cell candidate), o (output gate)"
)

PARAM_NAMES = [
    "lstm.weight_ih_l0", "lstm.weight_hh_l0", "lstm.bias_ih_l0", "lstm.bias_hh_l0",
    "l1.weight", "l1.bias", "l2.weight", "l2.bias", "l3.weight", "l3.bias",
    "l4.weight", "l4.bias", "head.weight", "head.bias",
]

EXPECTED_SHAPES = {
    "lstm.weight_ih_l0": [4 * LSTM_HIDDEN, LSTM_IN],
    "lstm.weight_hh_l0": [4 * LSTM_HIDDEN, LSTM_HIDDEN],
    "lstm.bias_ih_l0": [4 * LSTM_HIDDEN],
    "lstm.bias_hh_l0": [4 * LSTM_HIDDEN],
    "l1.weight": [HIDDEN, NODE_IN + LSTM_HIDDEN + ACTION_IN],
    "l1.bias": [HIDDEN],
    "l2.weight": [HIDDEN, HIDDEN],
    "l2.bias": [HIDDEN],
    "l3.weight": [HIDDEN, HIDDEN],
    "l3.bias": [HIDDEN],
    "l4.weight": [HIDDEN * 2, HIDDEN],
    "l4.bias": [HIDDEN * 2],
    "head.weight": [1, HIDDEN * 2],
    "head.bias": [1],
}

MODEL_ROOT = "pao"
MODEL_NAME = "pao_ts"


class Actor(torch.nn.Module):
    """Mirror of torch_nn::ActorImpl::forward in PerfectPaoCPP."""

    def __init__(self) -> None:
        super().__init__()
        self.lstm = torch.nn.LSTM(LSTM_IN, LSTM_HIDDEN, batch_first=True)
        self.l1 = torch.nn.Linear(NODE_IN + LSTM_HIDDEN + ACTION_IN, HIDDEN)
        self.l2 = torch.nn.Linear(HIDDEN, HIDDEN)
        self.l3 = torch.nn.Linear(HIDDEN, HIDDEN)
        self.l4 = torch.nn.Linear(HIDDEN, HIDDEN * 2)
        self.head = torch.nn.Linear(HIDDEN * 2, 1)

    def forward(self, x_imp, seq, action_feat, action_sample, action_id):
        h = self.lstm(seq)[1][0].squeeze(0)
        node = torch.cat([x_imp, h], dim=1)
        joint = torch.cat([node.index_select(0, action_sample), action_feat], dim=1)
        scores = torch.relu(self.l1(joint))
        scores = torch.relu(self.l2(scores))
        scores = torch.relu(self.l3(scores))
        scores = torch.relu(self.l4(scores))
        scores = self.head(scores).squeeze(-1)
        logits = torch.full((x_imp.size(0), 2048), -1e9, dtype=torch.float32)
        logits.index_put_((action_sample, action_id), scores)
        return logits


def extract(src: str, dst: str) -> None:
    os.makedirs(dst, exist_ok=True)
    path = os.path.join(src, "actor.pt")
    module = torch.jit.load(path, map_location="cpu")
    available = dict(module.named_parameters())
    extra = set(available) - set(PARAM_NAMES)
    assert not extra, (
        f"{path} has unexpected parameters {sorted(extra)}; the training side "
        "architecture changed, update PARAM_NAMES/EXPECTED_SHAPES accordingly"
    )
    params = {name: available[name].detach().clone() for name in PARAM_NAMES}
    for name in PARAM_NAMES:
        shape = list(params[name].shape)
        expected = EXPECTED_SHAPES[name]
        assert shape == expected, f"{name} shape {shape} != expected {expected}"
        print(f"  {name:20s} {tuple(shape)}")
    torch.save(params, os.path.join(dst, "actor.params.pt"))
    meta = os.path.join(src, "meta.txt")
    if os.path.exists(meta):
        with open(meta, "rb") as handle:
            with open(os.path.join(dst, "meta.txt"), "wb") as out:
                out.write(handle.read())


def zip_tensor_root(path: str) -> str:
    with zipfile.ZipFile(path) as zf:
        for name in zf.namelist():
            if name.endswith("/data/0"):
                return name[: -len("/data/0")]
    raise AssertionError(f"{path}: no <root>/data/0 entry found")


def write_manifest(model, dst: str) -> None:
    order = [name for name, _ in model.named_parameters()]
    assert order == PARAM_NAMES, f"parameter order changed: {order}"
    manifest = {
        "root": MODEL_ROOT,
        "dtype": "float32",
        "byteOrder": "little",
        "gateOrder": LSTM_GATE_ORDER,
        "parameters": [{"entry": i, "name": name, "shape": list(t.shape)}
                       for i, (name, t) in enumerate(model.named_parameters())],
    }
    with open(os.path.join(dst, f"{MODEL_NAME.rsplit('_ts', 1)[0]}_manifest.json"), "w") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
        handle.write("\n")


def script(src: str, dst: str) -> None:
    os.makedirs(dst, exist_ok=True)
    model = Actor()
    params = torch.load(os.path.join(src, "actor.params.pt"), map_location="cpu",
                        weights_only=True)
    missing = set(PARAM_NAMES) - set(params)
    assert not missing, missing
    model.load_state_dict(params, strict=True)
    model.eval()
    scripted = torch.jit.script(model)
    torch.jit.save(scripted, os.path.join(dst, f"{MODEL_NAME}.pt"))
    write_manifest(model, dst)
    archive = os.path.join(dst, f"{MODEL_NAME}.pt")
    root = zip_tensor_root(archive)
    manifest_path = os.path.join(dst, "pao_manifest.json")
    with open(manifest_path) as handle:
        manifest = json.load(handle)
    if root != manifest["root"]:
        manifest["root"] = root
        with open(manifest_path, "w") as handle:
            json.dump(manifest, handle, indent=2, sort_keys=True)
            handle.write("\n")
        print(f"  adjusted manifest root to '{root}'")
    # verify entry i really is named_parameters()[i]
    with zipfile.ZipFile(archive) as zf:
        for i, (name, tensor) in enumerate(model.named_parameters()):
            raw = zf.read(f"{root}/data/{i}")
            stored = torch.frombuffer(bytearray(raw), dtype=torch.float32)
            expected = tensor.detach().reshape(-1)
            assert torch.equal(stored, expected), f"entry {i} != {name}"
    size = os.path.getsize(archive)
    print(f"  wrote {MODEL_NAME}.pt ({size} bytes) + pao_manifest.json, entries verified")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_extract = sub.add_parser("extract", help="parameters out of the raw archive")
    p_extract.add_argument("--in", dest="src", required=True)
    p_extract.add_argument("--out", dest="dst", required=True)
    p_script = sub.add_parser("script", help="rebuild + TorchScript the actor")
    p_script.add_argument("--in", dest="src", required=True)
    p_script.add_argument("--out", dest="dst", required=True)
    args = parser.parse_args()
    if args.cmd == "extract":
        extract(args.src, args.dst)
    else:
        script(args.src, args.dst)


if __name__ == "__main__":
    sys.exit(main())

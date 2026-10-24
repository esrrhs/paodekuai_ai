// Hand-written forward pass over the TorchScript actor weights.
// Port of doudizhu_ai cpp/src/policy.cpp with the Paodekuai dimensions
// (one shared actor for all seats, LSTM 429->128 x 5, node 1188+128,
// action 149, MLP 1465 -> 256 -> 256 -> 256 -> 512 -> 1).
//
// 浮点逐位一致三要素: -ffp-contract=off; 累加顺序固定 (点积内累加后 +bias,
// LSTM 门预激活为 (dot(wIh,x)+bIh)+dot(wHh,h)+bHh); 全程 float32。
#include "paodekuai/policy.h"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <iterator>

#include "paodekuai/shared_data.h"
#include "pt_archive.h"

namespace paodekuai {

namespace {

using paodekuai::detail::ParamSpec;
using paodekuai::detail::ZipReader;

constexpr int kLstmGates = kPolicyLstmGates;  // 512 = 4 * 128
constexpr int kMlpHidden = kPolicyHidden;     // 256
constexpr int kMlpWide = kPolicyWide;         // 512

std::vector<float> LoadFloats(const std::uint8_t *p, std::size_t bytes) {
    std::vector<float> out(bytes / 4);
    if constexpr (std::endian::native == std::endian::little) {
        std::memcpy(out.data(), p, out.size() * sizeof(float));
    } else {
        for (std::size_t i = 0; i < out.size(); ++i) {
            std::uint32_t bits = (std::uint32_t)p[i * 4] | (std::uint32_t)p[i * 4 + 1] << 8 |
                                 (std::uint32_t)p[i * 4 + 2] << 16 |
                                 (std::uint32_t)p[i * 4 + 3] << 24;
            std::memcpy(&out[i], &bits, sizeof(float));
        }
    }
    return out;
}

float Sigmoid(float x) { return 1.0f / (1.0f + std::exp(-x)); }

// y = relu(W x + b) (relu 为 false 时不加 relu), W 为 [out][in] 行主序。
// 累加顺序与手写前向的其它语言实现保持一致: 先求点积再加偏置。
void Linear(const float *w, const float *b, int out, int in, const float *x,
            float *y, bool relu) {
    for (int o = 0; o < out; ++o) {
        const float *row = w + static_cast<std::size_t>(o) * static_cast<std::size_t>(in);
        float acc = 0.0f;
        for (int i = 0; i < in; ++i) {
            acc += row[i] * x[i];
        }
        acc += b[o];
        y[o] = relu && acc < 0.0f ? 0.0f : acc;
    }
}

}  // namespace

struct Policy::Actor {
    std::vector<float> wIh;   // [512][429]
    std::vector<float> wHh;   // [512][128]
    std::vector<float> bIh;   // [512]
    std::vector<float> bHh;   // [512]
    std::vector<float> l1w;   // [256][1465]
    std::vector<float> l1b;   // [256]
    std::vector<float> l2w;   // [256][256]
    std::vector<float> l2b;
    std::vector<float> l3w;   // [256][256]
    std::vector<float> l3b;
    std::vector<float> l4w;   // [512][256]
    std::vector<float> l4b;   // [512]
    std::vector<float> headw; // [1][512]
    std::vector<float> headb; // [1]

    std::vector<float> *Field(const std::string &name) {
        if (name == "lstm.weight_ih_l0") return &wIh;
        if (name == "lstm.weight_hh_l0") return &wHh;
        if (name == "lstm.bias_ih_l0") return &bIh;
        if (name == "lstm.bias_hh_l0") return &bHh;
        if (name == "l1.weight") return &l1w;
        if (name == "l1.bias") return &l1b;
        if (name == "l2.weight") return &l2w;
        if (name == "l2.bias") return &l2b;
        if (name == "l3.weight") return &l3w;
        if (name == "l3.bias") return &l3b;
        if (name == "l4.weight") return &l4w;
        if (name == "l4.bias") return &l4b;
        if (name == "head.weight") return &headw;
        if (name == "head.bias") return &headb;
        return nullptr;
    }

    // 只取最后一个时间步的 h。门块顺序 i, f, g, o (PyTorch 约定, 不是 cuDNN 的
    // i, h, f, c —— 写错 logits 会偏到千分位以上)。
    void LstmLastHidden(const float *seq, float *outH) const {
        std::array<float, kLstmGates> gates{};
        std::array<float, kPolicyLstmHidden> cell{};    // c 初始化为全零
        std::array<float, kPolicyLstmHidden> hidden{};  // h 初始化为全零

        for (int t = 0; t < kLstmSteps; ++t) {
            const float *x = seq + static_cast<std::size_t>(t) * kLstmIn;
            // 历史序列前部补零, 整步全零时可以跳过一次 429 维点积
            bool xNonZero = false;
            for (int i = 0; i < kLstmIn; ++i) {
                if (x[i] != 0.0f) {
                    xNonZero = true;
                    break;
                }
            }
            for (int g = 0; g < kLstmGates; ++g) {
                const float *rowI = wIh.data() + static_cast<std::size_t>(g) * kLstmIn;
                const float *rowH = wHh.data() + static_cast<std::size_t>(g) * kPolicyLstmHidden;
                float accI = 0.0f;
                if (xNonZero) {
                    for (int i = 0; i < kLstmIn; ++i) accI += rowI[i] * x[i];
                }
                float acc = accI + bIh[g];
                float accH = 0.0f;
                for (int h = 0; h < kPolicyLstmHidden; ++h) accH += rowH[h] * hidden[h];
                acc = acc + accH + bHh[g];
                gates[g] = acc;
            }
            for (int k = 0; k < kPolicyLstmHidden; ++k) {
                const float iGate = Sigmoid(gates[k]);
                const float fGate = Sigmoid(gates[kPolicyLstmHidden + k]);
                const float gGate = std::tanh(gates[2 * kPolicyLstmHidden + k]);
                const float oGate = Sigmoid(gates[3 * kPolicyLstmHidden + k]);
                cell[k] = fGate * cell[k] + iGate * gGate;
                hidden[k] = oGate * std::tanh(cell[k]);
            }
        }
        std::memcpy(outH, hidden.data(), sizeof(float) * kPolicyLstmHidden);
    }
};

namespace {

struct LayerSpec {
    const char *name;
    int rows;
    int cols;  // -1 = bias
};

constexpr LayerSpec kLayers[] = {
    {"lstm.weight_ih_l0", kLstmGates, kLstmIn},
    {"lstm.weight_hh_l0", kLstmGates, kPolicyLstmHidden},
    {"lstm.bias_ih_l0", kLstmGates, -1},
    {"lstm.bias_hh_l0", kLstmGates, -1},
    {"l1.weight", kMlpHidden, kPolicyJointSize},
    {"l1.bias", kMlpHidden, -1},
    {"l2.weight", kMlpHidden, kMlpHidden},
    {"l2.bias", kMlpHidden, -1},
    {"l3.weight", kMlpHidden, kMlpHidden},
    {"l3.bias", kMlpHidden, -1},
    {"l4.weight", kMlpWide, kMlpHidden},
    {"l4.bias", kMlpWide, -1},
    {"head.weight", 1, kMlpWide},
    {"head.bias", 1, -1},
};

const LayerSpec *FindLayer(const std::string &name) {
    for (const LayerSpec &layer : kLayers) {
        if (name == layer.name) return &layer;
    }
    return nullptr;
}

bool ShapeMatches(const std::vector<int> &shape, const LayerSpec &layer) {
    if (layer.cols < 0) {
        // bias: one dimension
        return shape.size() == 1 && shape[0] == layer.rows;
    }
    return shape.size() == 2 && shape[0] == layer.rows && shape[1] == layer.cols;
}

}  // namespace

bool Policy::Load(const std::string &dir) {
    error_.clear();
    const std::string manifestPath = dir + "/pao_manifest.json";
    const std::string archivePath = dir + "/pao_ts.pt";
    std::string manifest;
    {
        std::FILE *fp = std::fopen(manifestPath.c_str(), "rb");
        if (fp == nullptr) {
            error_ = "missing manifest " + manifestPath +
                     " (export with tools/export_actor.py first)";
            return false;
        }
        std::fseek(fp, 0, SEEK_END);
        const long sz = std::ftell(fp);
        std::fseek(fp, 0, SEEK_SET);
        const std::size_t want = static_cast<std::size_t>(sz > 0 ? sz : 0);
        manifest.resize(want);
        if (want > 0 && std::fread(manifest.data(), 1, want, fp) != want) {
            std::fclose(fp);
            error_ = "failed to read manifest " + manifestPath;
            return false;
        }
        std::fclose(fp);
    }

    std::string root;
    std::vector<paodekuai::detail::ParamSpec> specs;
    if (!paodekuai::detail::ParseManifest(manifest, &root, &specs, &error_)) {
        error_ = manifestPath + ": " + error_;
        return false;
    }

    paodekuai::detail::ZipReader zip;
    std::string zipErr;
    if (!zip.Open(archivePath, &zipErr)) {
        error_ = "failed to load weights: " + zipErr;
        return false;
    }

    // 层数与层名都必须与本工程的内建结构一一对应
    constexpr std::size_t kLayerCount = std::size(kLayers);
    if (specs.size() != kLayerCount) {
        error_ = manifestPath + ": parameter count " + std::to_string(specs.size()) +
                 ", expected " + std::to_string(kLayerCount);
        return false;
    }
    for (std::size_t i = 0; i < specs.size(); ++i) {
        for (std::size_t j = i + 1; j < specs.size(); ++j) {
            if (specs[i].name == specs[j].name) {
                error_ = manifestPath + ": duplicate layer " + specs[i].name;
                return false;
            }
        }
    }

    auto actor = std::make_unique<Actor>();
    std::array<bool, kLayerCount> got{};
    got.fill(false);
    for (const paodekuai::detail::ParamSpec &spec : specs) {
        const LayerSpec *layer = FindLayer(spec.name);
        if (layer == nullptr) {
            error_ = "unknown layer in manifest: " + spec.name;
            return false;
        }
        if (!ShapeMatches(spec.shape, *layer)) {
            error_ = "layer " + spec.name + " shape mismatch (network differs)";
            return false;
        }
        const std::string entryName = root + "/data/" + std::to_string(spec.entry);
        const paodekuai::detail::ZipEntry *entry = zip.Find(entryName);
        if (entry == nullptr) {
            error_ = "missing zip entry " + entryName + " (" + archivePath + ")";
            return false;
        }
        std::size_t numel = 1;
        for (int dim : spec.shape) numel *= static_cast<std::size_t>(dim);
        std::uint64_t size = 0;
        const std::uint8_t *raw = zip.StoredData(*entry, &size, &zipErr);
        if (raw == nullptr) {
            error_ = archivePath + ": " + zipErr;
            return false;
        }
        if (size != numel * sizeof(float)) {
            error_ = "entry " + entryName + " (" + spec.name + ") size " +
                     std::to_string(size) + " != expected " + std::to_string(numel * sizeof(float));
            return false;
        }
        *actor->Field(spec.name) = LoadFloats(raw, static_cast<std::size_t>(size));
        got[static_cast<std::size_t>(layer - kLayers)] = true;
    }
    for (std::size_t i = 0; i < kLayerCount; ++i) {
        if (!got[i]) {
            error_ = std::string("manifest missing layer ") + kLayers[i].name;
            return false;
        }
    }
    actor_ = std::move(actor);
    return true;
}

std::string Policy::DefaultModelDir() {
    return ResolveSharedData(DefaultModelRel());
}

std::vector<float> Policy::Score(const float *xImp, const float *seq,
                                 const float *actionFeat, int options) const {
    assert(actor_ != nullptr);
    if (options <= 0) return {};

    std::vector<float> h(kPolicyLstmHidden);
    actor_->LstmLastHidden(seq, h.data());

    // node = concat(xImp, h_last)  ->  1316
    std::vector<float> node(kPolicyNodeDim);
    std::memcpy(node.data(), xImp, sizeof(float) * kNodeSize);
    std::memcpy(node.data() + kNodeSize, h.data(), sizeof(float) * kPolicyLstmHidden);

    // l1 的 node 部分对同一状态的所有候选相同, 先算一次; 动作部分只累加非零维
    std::vector<float> pre1(actor_->l1b.begin(), actor_->l1b.end());
    for (int o = 0; o < kMlpHidden; ++o) {
        const float *row = actor_->l1w.data() + static_cast<std::size_t>(o) * kPolicyJointSize;
        float acc = pre1[o];
        for (int j = 0; j < kPolicyNodeDim; ++j) {
            acc += row[j] * node[j];
        }
        pre1[o] = acc;
    }

    std::vector<int> nzIdx;
    nzIdx.reserve(kActionSize);
    std::vector<float> buf1(kMlpHidden), buf2(kMlpHidden), buf3(kMlpWide);
    std::vector<float> logits(options);
    for (int i = 0; i < options; ++i) {
        const float *feat = actionFeat + static_cast<std::size_t>(i) * kActionSize;
        nzIdx.clear();
        for (int j = 0; j < kActionSize; ++j) {
            if (feat[j] != 0.0f) nzIdx.push_back(j);  // 149 维里大部分是 0
        }
        for (int o = 0; o < kMlpHidden; ++o) {
            const float *row = actor_->l1w.data() +
                               static_cast<std::size_t>(o) * kPolicyJointSize + kPolicyNodeDim;
            float acc = pre1[o];
            for (int j : nzIdx) acc += row[j] * feat[j];
            buf1[o] = acc < 0.0f ? 0.0f : acc;  // relu(l1)
        }
        Linear(actor_->l2w.data(), actor_->l2b.data(), kMlpHidden, kMlpHidden,
               buf1.data(), buf2.data(), true);
        Linear(actor_->l3w.data(), actor_->l3b.data(), kMlpHidden, kMlpHidden,
               buf2.data(), buf1.data(), true);
        Linear(actor_->l4w.data(), actor_->l4b.data(), kMlpWide, kMlpHidden,
               buf1.data(), buf3.data(), true);
        float acc = actor_->headb[0];
        for (int j = 0; j < kMlpWide; ++j) acc += actor_->headw[j] * buf3[j];
        logits[i] = acc;  // head 不加 relu
    }
    return logits;
}

Policy::Policy() = default;
Policy::~Policy() = default;

Policy::Decision Policy::Decide(const PublicView &view) const {
    Decision d;
    std::vector<LegalOption> options = legalOptions(view);
    d.options.reserve(options.size());
    for (const LegalOption &o : options) d.options.push_back(o.concrete);
    if (options.empty()) return d;
    if (options.size() == 1) {
        d.slot = 0;
        d.move = options.front().concrete;
        d.logits = {0.0f};
        d.probs = {1.0};
        return d;
    }
    float xImp[kNodeSize];
    float seq[kSeqSize];
    encode(view, xImp, seq);
    std::vector<float> actionFeat(static_cast<size_t>(options.size()) * kActionSize);
    for (size_t i = 0; i < options.size(); ++i) {
        std::memcpy(actionFeat.data() + i * kActionSize, options[i].feature.data(),
                    sizeof(float) * kActionSize);
    }
    d.logits = Score(xImp, seq, actionFeat.data(), static_cast<int>(options.size()));
    int best = 0;
    for (int i = 1; i < static_cast<int>(options.size()); ++i) {
        if (d.logits[i] > d.logits[best]) best = i;
    }
    d.slot = best;
    d.move = options[best].concrete;
    // softmax 用 double, 只影响展示, 不影响 argmax
    double maxLogit = *std::max_element(d.logits.begin(), d.logits.end());
    double sum = 0.0;
    d.probs.resize(d.logits.size());
    for (size_t i = 0; i < d.logits.size(); ++i) {
        d.probs[i] = std::exp(static_cast<double>(d.logits[i]) - maxLogit);
        sum += d.probs[i];
    }
    for (float &p : d.probs) p = static_cast<float>(p / sum);
    return d;
}

}  // namespace paodekuai

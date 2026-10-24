// Golden fixture dumper for Paodekuai — the counterpart of doudizhu_ai's
// tools/pddump.cpp. Compiled against the PerfectPaoCPP training side, it plays
// whole games with the actor policy occupying every seat and dumps, for every
// decision point, the exact features fed to the network, the candidate set,
// the libtorch logits and the chosen move. The Java / Go / C++ runtimes replay
// this file in their parity tests.
//
// Build (from the paodekuai_ai repo root, $TORCH = the libtorch that built
// PerfectPaoCPP):
//   clang++ -std=c++20 -O2 -o /tmp/pddump tools/pddump.cpp \
//       -I../PerfectPaoCPP/src \
//       -I$TORCH/include -I$TORCH/include/torch/csrc/api/include \
//       ../PerfectPaoCPP/build/libperfectpao.a \
//       -L$TORCH/lib -ltorch -ltorch_cpu -lc10 -lpthread -ldl
//   DYLD_LIBRARY_PATH=$TORCH/lib /tmp/pddump 24 777 data/models/latest > golden.txt
//
// Usage: pddump <ngames> <seed0> <modelDir>
// Output format (tokens separated by whitespace, floats " %.9g", 20 per line):
//   PAOGOLDEN 1 <games> <seed0>
//   GAME <g>
//   DEAL <seat> <cards>        x3
//   LEAD <seat>                the designated first leader
//   DEC <d> TURN <seat> HIST <history length>
//   XIMP 1188   <floats>       node binary part then scalars, one stream
//   SEQ 2145    <floats>       15 history matrices flattened
//   OPT <L>
//   OPTLINE <cards>            x L, in candidate order ("" denotes a pass)
//   AFEAT 149   <floats>       after each OPTLINE
//   LOGIT <L>   <floats>       libtorch logits indexed by candidate slot
//   CHOSEN <slot> <cards>
//   ACT <seat> <cards>
//   ENDDEC
//   ... (repeat per decision)
//   ENDGAME
//
// Cards use the training side's alphabet "3456789TJQKA2".

#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include <torch/torch.h>

#include "pdk/cards.h"
#include "pdk/features.h"
#include "pdk/game.h"
#include "pdk/oracle.h"
#include "torch_nn/models.h"

namespace {

void dumpFloats(const float *p, int n) {
    for (int i = 0; i < n; ++i) {
        std::printf(" %.9g", p[i]);
        if ((i + 1) % 20 == 0) std::printf("\n");
    }
    if (n % 20 != 0) std::printf("\n");
}

// One network decision for the acting seat of `g`; fills the logits indexed by
// the local candidate slot (the abstractId the training side assigns) and
// returns the argmax slot. Mirrors algo/eval_pimc.cpp.
int ourMove(torch_nn::Actor &actor, const pdk::Game &g,
            std::vector<float> &logitsOut) {
    std::vector<pdk::LegalOption> opts = pdk::legalOptions(g);
    torch::Tensor xImpT = torch::empty({1, torch_nn::kImpInput}, torch::kFloat32);
    torch::Tensor seqT = torch::empty({1, torch_nn::kLstmSteps, torch_nn::kLstmIn},
                                      torch::kFloat32);
    int64_t L = static_cast<int64_t>(opts.size());
    torch::Tensor actFeatT = torch::empty({L, torch_nn::kActionInput}, torch::kFloat32);
    torch::Tensor actSampleT = torch::zeros({L}, torch::kLong);
    torch::Tensor actIdT = torch::empty({L}, torch::kLong);

    pdk::EncodedState e = pdk::encodeState(g);
    float *pXImp = xImpT.data_ptr<float>();
    for (int j = 0; j < pdk::kNodeBin; ++j)
        pXImp[j] = float(e.imp[static_cast<size_t>(j)]);
    std::copy(e.scalar.begin(), e.scalar.end(), pXImp + pdk::kNodeBin);
    float *pSeq = seqT.data_ptr<float>();
    for (int k = 0; k < pdk::kHistoryLen; ++k) {
        const uint8_t *src = e.imp.data() + (pdk::kStaticMatrices + k) * pdk::kCardMat;
        float *dst = pSeq + k * pdk::kCardMat;
        for (int j = 0; j < pdk::kCardMat; ++j) dst[j] = float(src[j]);
    }
    float *pActFeat = actFeatT.data_ptr<float>();
    int64_t *pActId = actIdT.data_ptr<int64_t>();
    for (int64_t i = 0; i < L; ++i) {
        std::memcpy(pActFeat + i * torch_nn::kActionInput,
                    opts[static_cast<size_t>(i)].feature.data(),
                    torch_nn::kActionInput * sizeof(float));
        pActId[i] = opts[static_cast<size_t>(i)].abstractId;
    }

    torch::Tensor logitsT = actor->forward(xImpT, seqT, actFeatT, actSampleT, actIdT);
    const float *row = logitsT.data_ptr<float>();
    int best = 0;
    float bestVal = -1e30f;
    logitsOut.resize(opts.size());
    for (size_t i = 0; i < opts.size(); ++i) {
        float v = row[opts[static_cast<size_t>(i)].abstractId];
        logitsOut[i] = v;
        if (v > bestVal) {
            bestVal = v;
            best = static_cast<int>(i);
        }
    }
    return best;
}

void dumpDecision(torch_nn::Actor &actor, pdk::Game &g, int index) {
    const int turn = g.turn;
    const int hist = static_cast<int>(g.seq.size());
    pdk::EncodedState e = pdk::encodeState(g);
    std::vector<pdk::LegalOption> opts = pdk::legalOptions(g);
    std::vector<float> logits;
    const int slot = ourMove(actor, g, logits);

    std::printf("DEC %d TURN %d HIST %d\n", index, turn, hist);
    std::printf("XIMP %d", pdk::kNodeSize);
    std::vector<float> xImp(pdk::kNodeSize);
    for (int j = 0; j < pdk::kNodeBin; ++j)
        xImp[static_cast<size_t>(j)] = float(e.imp[static_cast<size_t>(j)]);
    std::copy(e.scalar.begin(), e.scalar.end(), xImp.begin() + pdk::kNodeBin);
    dumpFloats(xImp.data(), pdk::kNodeSize);

    std::printf("SEQ %d\n", pdk::kHistoryLen * pdk::kCardMat);
    std::vector<float> seq(pdk::kHistoryLen * pdk::kCardMat);
    for (int k = 0; k < pdk::kHistoryLen; ++k)
        for (int j = 0; j < pdk::kCardMat; ++j)
            seq[static_cast<size_t>(k * pdk::kCardMat + j)] =
                float(e.imp[static_cast<size_t>((pdk::kStaticMatrices + k) * pdk::kCardMat + j)]);
    dumpFloats(seq.data(), static_cast<int>(seq.size()));

    std::printf("OPT %d\n", static_cast<int>(opts.size()));
    for (const auto &o : opts) {
        // pass 没有牌面, 用 "-" 占位, 保证 token 流不串位
        std::printf("OPTLINE %s\n",
                    o.concrete.empty() ? "-" : o.concrete.str().c_str());
        std::printf("AFEAT %d", pdk::kActionSize);
        dumpFloats(o.feature.data(), pdk::kActionSize);
    }
    std::printf("LOGIT %d", static_cast<int>(logits.size()));
    dumpFloats(logits.data(), static_cast<int>(logits.size()));
    const pdk::CardSet &chosen = opts[static_cast<size_t>(slot)].concrete;
    std::printf("CHOSEN %d %s\n", slot, chosen.empty() ? "-" : chosen.str().c_str());
    std::printf("ACT %d %s\n", turn, chosen.empty() ? "-" : chosen.str().c_str());
    std::printf("ENDDEC\n");

    g.step(chosen);
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: pddump <ngames> <seed0> <modelDir>\n");
        return 1;
    }
    const int games = std::atoi(argv[1]);
    const uint64_t seed0 = std::strtoull(argv[2], nullptr, 10);
    const std::string modelDir = argv[3];

    torch_nn::Actor actor;
    actor->load(modelDir + "/actor.pt");
    torch::NoGradGuard noGrad;

    std::printf("PAOGOLDEN 1 %d %llu\n", games,
                static_cast<unsigned long long>(seed0));

    int decisions = 0;
    for (int gi = 0; gi < games; ++gi) {
        pdk::Rng rng(seed0 + static_cast<uint64_t>(gi));
        pdk::Game g;
        g.deal(rng);

        std::printf("GAME %d\n", gi);
        for (int s = 0; s < 3; ++s)
            std::printf("DEAL %d %s\n", s, g.hand[static_cast<size_t>(s)].str().c_str());
        std::printf("LEAD %d\n", g.turn);
        int guard = 0;
        while (!g.over && guard < 10000) {
            dumpDecision(actor, g, decisions++);
            ++guard;
        }
        std::printf("ENDGAME\n");
    }
    std::fprintf(stderr, "dumped %d decisions over %d games\n", decisions, games);
    return 0;
}

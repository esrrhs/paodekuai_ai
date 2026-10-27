// Golden parity test: replays the training-side fixture, comparing the
// candidate set, xImp, seq, action features (all bit-exact) and the logits
// (|Δ| < 1e-3, argmax must match) for every decision.
#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <string>
#include <vector>

#include "paodekuai/api.h"
#include "golden_reader.h"

#ifdef PAODEKUAI_HAVE_ZLIB
#include <zlib.h>
#endif

namespace fs = std::filesystem;

namespace {

// gunzip when zlib is available; otherwise accept a pre-decompressed file.
bool ReadGoldenFile(const std::string &path, paodekuai::test::Golden *golden) {
#ifdef PAODEKUAI_HAVE_ZLIB
    std::FILE *fp = std::fopen(path.c_str(), "rb");
    if (fp == nullptr) return false;
    std::vector<char> compressed;
    char buf[65536];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof buf, fp)) > 0) compressed.insert(compressed.end(), buf, buf + n);
    std::fclose(fp);

    std::string text;
    z_stream zs{};
    inflateInit2(&zs, 16 + MAX_WBITS);
    zs.next_in = reinterpret_cast<Bytef *>(compressed.data());
    zs.avail_in = static_cast<uInt>(compressed.size());
    int rc = Z_BUF_ERROR;
    char chunk[65536];
    while (rc == Z_BUF_ERROR || rc == Z_OK) {
        zs.next_out = reinterpret_cast<Bytef *>(chunk);
        zs.avail_out = sizeof chunk;
        rc = inflate(&zs, Z_NO_FLUSH);
        text.append(chunk, sizeof chunk - zs.avail_out);
        if (rc == Z_STREAM_END) break;
        if (rc != Z_OK && rc != Z_BUF_ERROR) break;
    }
    inflateEnd(&zs);
    if (rc != Z_STREAM_END) return false;
    std::FILE *mem = fmemopen(text.data(), text.size(), "rb");
    bool ok = paodekuai::test::ReadGoldenStream(mem, golden);
    std::fclose(mem);
    return ok;
#else
    std::FILE *fp = std::fopen(path.c_str(), "rb");
    if (fp == nullptr) return false;
    bool ok = paodekuai::test::ReadGoldenStream(fp, golden);
    std::fclose(fp);
    return ok;
#endif
}

}  // namespace

TEST(PaodekuaiParity, FeaturesMatchGoldenBitExact)
{
    const std::string path = paodekuai::ResolveSharedData("data/golden/golden24.txt.gz");
    ASSERT_TRUE(std::filesystem::exists(path)) << path;

    paodekuai::test::Golden golden;
    ASSERT_TRUE(ReadGoldenFile(path, &golden));
    ASSERT_GE(golden.games, 24);
    int total = 0;
    for (const auto &g : golden.gameList) total += static_cast<int>(g.decisions.size());
    ASSERT_GT(total, 400);

    std::vector<std::string> diffs;
    for (const auto &game : golden.gameList) {
        std::array<paodekuai::CardSet, 3> hands;
        for (int s = 0; s < 3; ++s) hands[s] = paodekuai::CardSet::parse(game.deals[s]);
        paodekuai::GameState state;
        state.hand = hands;
        state.turn = game.lead;
        state.lastPlayer = game.lead;
        for (const auto &dec : game.decisions) {
            ASSERT_EQ(dec.turn, state.turn) << "game " << game.index;
            ASSERT_EQ(dec.hist, state.seq.size()) << "game " << game.index;
            paodekuai::PublicView view = state.getPublicView(state.turn);
            std::vector<float> ximp(paodekuai::kNodeSize);
            std::vector<float> seq(paodekuai::kSeqSize);
            paodekuai::encode(view, ximp.data(), seq.data());
            for (size_t i = 0; i < ximp.size(); ++i) {
                if (ximp[i] != dec.ximp[i]) {
                    diffs.push_back("game " + std::to_string(game.index) + " ximp[" +
                                    std::to_string(i) + "]");
                    break;
                }
            }
            for (size_t i = 0; i < seq.size(); ++i) {
                if (seq[i] != dec.seq[i]) {
                    diffs.push_back("game " + std::to_string(game.index) + " seq[" +
                                    std::to_string(i) + "]");
                    break;
                }
            }
            std::vector<paodekuai::LegalOption> options = paodekuai::legalOptions(view);
            if (options.size() != dec.optLines.size()) {
                diffs.push_back("game " + std::to_string(game.index) + ": options " +
                                std::to_string(options.size()) + " != " +
                                std::to_string(dec.optLines.size()));
            } else {
                for (size_t i = 0; i < options.size(); ++i) {
                    const std::string mine = options[i].concrete.str();
                    if (mine != dec.optLines[i]) {
                        diffs.push_back("game " + std::to_string(game.index) + ": option " +
                                        std::to_string(i) + " '" + mine + "' != '" +
                                        dec.optLines[i] + "'");
                    }
                    for (size_t j = 0; j < paodekuai::kActionSize; ++j) {
                        if (options[i].feature[j] != dec.afeat[i][j]) {
                            diffs.push_back("game " + std::to_string(game.index) + ": afeat " +
                                            std::to_string(i) + "[" + std::to_string(j) + "]");
                            break;
                        }
                    }
                }
            }
            ASSERT_LT(dec.chosenSlot, static_cast<int>(options.size()));
            state.step(options[dec.chosenSlot].concrete);
        }
    }
    std::string head;
    for (size_t i = 0; i < diffs.size() && i < 5; ++i) head += diffs[i] + "\n";
    EXPECT_TRUE(diffs.empty()) << "feature mismatches: " << diffs.size() << "\n" << head;
}

TEST(PaodekuaiParity, LogitsMatchGolden)
{
    paodekuai::Policy policy;
    ASSERT_TRUE(policy.Load(paodekuai::Policy::DefaultModelDir())) << policy.Error();
    const std::string path = paodekuai::ResolveSharedData("data/golden/golden24.txt.gz");
    paodekuai::test::Golden golden;
    ASSERT_TRUE(ReadGoldenFile(path, &golden));

    constexpr float kLogitTolerance = 1e-3f;
    double maxDeviation = 0.0;
    int mismatches = 0;
    int decisions = 0;
    for (const auto &game : golden.gameList) {
        std::array<paodekuai::CardSet, 3> hands;
        for (int s = 0; s < 3; ++s) hands[s] = paodekuai::CardSet::parse(game.deals[s]);
        paodekuai::GameState state;
        state.hand = hands;
        state.turn = game.lead;
        state.lastPlayer = game.lead;
        for (const auto &dec : game.decisions) {
            paodekuai::PublicView view = state.getPublicView(state.turn);
            std::vector<paodekuai::LegalOption> options = paodekuai::legalOptions(view);
            ASSERT_EQ(options.size(), dec.afeat.size());
            std::vector<float> actionFeat(options.size() * paodekuai::kActionSize);
            for (size_t i = 0; i < options.size(); ++i)
                std::memcpy(actionFeat.data() + i * paodekuai::kActionSize,
                            options[i].feature.data(), sizeof(float) * paodekuai::kActionSize);
            std::vector<float> ximp(paodekuai::kNodeSize);
            std::vector<float> seq(paodekuai::kSeqSize);
            paodekuai::encode(view, ximp.data(), seq.data());
            std::vector<float> logits = policy.Score(ximp.data(), seq.data(), actionFeat.data(),
                                                     static_cast<int>(options.size()));
            int argmax = 0;
            int goldenArgmax = 0;
            for (size_t i = 0; i < logits.size(); ++i) {
                maxDeviation = std::max(maxDeviation,
                                        static_cast<double>(std::fabs(logits[i] - dec.logits[i])));
                if (logits[i] > logits[argmax]) argmax = static_cast<int>(i);
                if (dec.logits[i] > dec.logits[goldenArgmax]) goldenArgmax = static_cast<int>(i);
            }
            EXPECT_LT(maxDeviation, static_cast<double>(kLogitTolerance));
            if (argmax != goldenArgmax) ++mismatches;
            EXPECT_EQ(argmax, dec.chosenSlot);
            state.step(options[argmax].concrete);
            ++decisions;
        }
    }
    EXPECT_EQ(mismatches, 0);
    std::printf("replayed %d decisions, max logit deviation %.3e, argmax mismatches %d\n",
                decisions, maxDeviation, mismatches);
}

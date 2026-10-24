// Node / action feature engineering, port of the training side
// pdk/features.cpp operating on PublicView (public information only).
#include "paodekuai/perfect_pao_features.h"

#include <algorithm>
#include <cstring>

#include "paodekuai/oracle.h"

namespace paodekuai {

namespace {

// row offsets inside the 11 x 13 card matrix
constexpr int R_SOLO = 4;
constexpr int R_PAIR = 5;
constexpr int R_TRIO = 6;
constexpr int R_BOMB = 7;
constexpr int R_SOLOCHAIN = 8;
constexpr int R_PAIRCHAIN = 9;
constexpr int R_TRIOCHAIN = 10;

// Whether rank r sits inside a run of >= minLen consecutive ranks (3..A)
// each holding at least `need` copies.
bool inChain(const int8_t *cnt, int r, int minLen, int need) {
    if (r >= kChainRanks) return false;
    int lo = r, hi = r;
    while (lo - 1 >= 0 && cnt[lo - 1] >= need) --lo;
    while (hi + 1 < kChainRanks && cnt[hi + 1] >= need) ++hi;
    return hi - lo + 1 >= minLen;
}

// Whether no remaining card of the same pattern category can beat this move
// (bomb aside): the solo 2, the AA pair/triple, the A bomb, or a chain that
// tops out at A.
bool isLargest(const MoveInfo &a) {
    switch (a.type) {
        case MT_SINGLE: return a.rank == 12;  // the only 2
        case MT_BOMB:
        case MT_PAIR:
        case MT_TRIPLE:
        case MT_TRIPLE_TWO:
            return a.rank == 11;  // rank A (a pair of 2s cannot exist)
        case MT_STRAIGHT:
        case MT_PAIR_STRAIGHT:
        case MT_TRIPLE_STRAIGHT:
        case MT_PLANE_SOLO:
        case MT_PLANE_PAIR:
            return a.rank + a.len - 1 == 11;  // chain ends at A
        default:
            return false;
    }
}

}  // namespace

void cardMatrix(const CardSet &cs, float *out) {
    std::fill(out, out + kCardMat, 0.0f);
    for (int r = 0; r < kRanks; ++r) {
        for (int k = 0; k < cs.c[r]; ++k)
            out[k * kRanks + r] = 1.0f;
        if (cs.c[r] >= 1) out[R_SOLO * kRanks + r] = 1.0f;
        if (cs.c[r] >= 2) out[R_PAIR * kRanks + r] = 1.0f;
        if (cs.c[r] >= 3) out[R_TRIO * kRanks + r] = 1.0f;
        if (cs.c[r] == 4) out[R_BOMB * kRanks + r] = 1.0f;
    }
    for (int r = 0; r < kChainRanks; ++r) {
        if (inChain(cs.c.data(), r, kMinStraight, 1))
            out[R_SOLOCHAIN * kRanks + r] = 1.0f;
        if (inChain(cs.c.data(), r, kMinPairChain, 2))
            out[R_PAIRCHAIN * kRanks + r] = 1.0f;
        if (inChain(cs.c.data(), r, kMinPlane, 3))
            out[R_TRIOCHAIN * kRanks + r] = 1.0f;
    }
}

void encode(const PublicView &view, float *xImp, float *seq) {
    const int seat = view.viewingPlayerId;
    int prev = view.prevSeat();
    int nxt = view.nextSeat();

    // replay the public history into per-seat played piles
    std::array<CardSet, 3> played{};
    int bombCount = 0;
    for (const MoveRecord &rec : view.history) {
        if (!rec.cards.empty()) {
            played[rec.playerId].add(rec.cards);
            if (detectMove(rec.cards).type == MT_BOMB) ++bombCount;
        }
    }

    // the combined unknown cards of the two opponents:
    // deck(48) - my hand - everything played
    CardSet others;
    for (int r = 0; r <= 10; ++r) others.add(r, 4);
    others.add(11, 3);
    others.add(12, 1);
    others.sub(view.myHand);
    for (int s = 0; s < 3; ++s) others.sub(played[s]);

    float matrices[kImpMatrices * kCardMat] = {};  // 未写满(历史为空)的槽位必须为 0
    cardMatrix(view.myHand, matrices + 0 * kCardMat);
    cardMatrix(others, matrices + 1 * kCardMat);
    cardMatrix(played[seat], matrices + 2 * kCardMat);
    cardMatrix(played[prev], matrices + 3 * kCardMat);
    cardMatrix(played[nxt], matrices + 4 * kCardMat);
    cardMatrix(view.lastMove[seat], matrices + 5 * kCardMat);
    cardMatrix(view.lastMove[prev], matrices + 6 * kCardMat);
    cardMatrix(view.lastMove[nxt], matrices + 7 * kCardMat);
    // last 15 moves, chronological, zero-padded at the front
    int hlen = std::min<int>(kHistoryLen, static_cast<int>(view.history.size()));
    for (int i = 0; i < hlen; ++i) {
        const CardSet &m = view.history[view.history.size() - hlen + i].cards;
        cardMatrix(m, matrices + (kStaticMatrices + (kHistoryLen - hlen) + i) * kCardMat);
    }

    auto oneHot = [](float *dst, int n, int value) {
        if (value >= 0 && value < n) dst[value] = 1.0f;
    };
    float scalars[kImpScalars] = {};
    oneHot(scalars, kHandHot, view.cardCounts[prev] - 1);
    oneHot(scalars + kHandHot, kHandHot, view.cardCounts[nxt] - 1);
    oneHot(scalars + 2 * kHandHot, kBombHot, std::min(bombCount, kBombHot - 1));
    scalars[2 * kHandHot + kBombHot + 0] = (view.lastPlayerId == seat) ? 1.0f : 0.0f;
    scalars[2 * kHandHot + kBombHot + 1] = (view.cardCounts[nxt] == 1) ? 1.0f : 0.0f;
    scalars[2 * kHandHot + kBombHot + 2] = (view.cardCounts[prev] == 1) ? 1.0f : 0.0f;

    std::memcpy(xImp, matrices, sizeof(float) * kNodeBin);
    std::memcpy(xImp + kNodeBin, scalars, sizeof(float) * kImpScalars);
    std::memcpy(seq, matrices + kStaticMatrices * kCardMat, sizeof(float) * kSeqSize);
}

std::vector<LegalOption> legalOptions(const PublicView &view) {
    std::vector<LegalOption> out;
    int seat = view.viewingPlayerId;
    int nxt = view.nextSeat();

    std::vector<CardSet> concrete;
    if (view.toBeat.empty()) {
        concrete = genAllMoves(view.myHand);
        if (view.history.empty()) {
            // first hand must contain a 3
            size_t w = 0;
            for (auto &m : concrete)
                if (m.c[0] >= 1) concrete[w++] = std::move(m);
            concrete.resize(w);
        } else if (view.cardCounts[nxt] == 1) {
            // next player reported: leading a solo must top the largest solo
            int largest = -1;
            for (int r = 0; r < kRanks; ++r)
                if (view.myHand.c[r] >= 1) largest = r;
            size_t w = 0;
            for (auto &m : concrete) {
                MoveInfo info = detectMove(m);
                if (info.type != MT_SINGLE || info.rank == largest)
                    concrete[w++] = std::move(m);
            }
            concrete.resize(w);
        }
    } else {
        concrete = legalMoves(view.myHand, view.toBeat);
    }

    out.reserve(concrete.size());
    for (const CardSet &chosen : concrete) {
        MoveInfo info = detectMove(chosen);
        if (info.type == MT_WRONG && !chosen.empty()) continue;
        LegalOption o;
        o.slot = static_cast<int>(out.size());
        o.concrete = chosen;
        int size = chosen.total();
        float card[kCardMat];
        cardMatrix(chosen, card);
        std::memcpy(o.feature.data(), card, sizeof(float) * kCardMat);
        float *ex = o.feature.data() + kCardMat;
        ex[0] = (info.type == MT_BOMB) ? 1.0f : 0.0f;
        ex[1] = isLargest(info) ? 1.0f : 0.0f;
        ex[2] = (size > 0 && size == view.cardCounts[nxt]) ? 1.0f : 0.0f;
        ex[3] = (size > 0 && size == view.cardCounts[view.prevSeat()]) ? 1.0f : 0.0f;
        CardSet after = view.myHand;
        after.sub(chosen);
        ex[4] = float(minSteps(after)) / float(kHandSize);
        ex[5] = 1.0f;  // validity marker, needed for the all-zero pass matrix
        out.push_back(std::move(o));
    }
    return out;
}

}  // namespace paodekuai

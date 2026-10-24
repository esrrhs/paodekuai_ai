// Node / action feature engineering for Paodekuai, bit-identical to the
// training side pdk/features.cpp. Encoded from PublicView only.
//
// 11x13 card matrix (row-major row*13+rank):
//   rows 0-3 count one-hot; 4 solo; 5 pair; 6 trio; 7 bomb (==4);
//   8/9/10 inside a solo/pair/trio chain.
//
// Node xImp[1188] = 8 static matrices (1144) + 44 scalars:
//   own hand / both opponents combined / own & prev & next played cards /
//   own & prev & next last move;
//   scalars = oneHot(prev count-1,16) + oneHot(next count-1,16)
//           + oneHot(min(bombCount,8),9) + lastPlayer==seat
//           + next reported + prev reported.
//
// History seq[2145] = last 15 moves (passes are empty matrices) chronological,
// zero-padded at the front; LSTM view = 5 steps x 3 moves x 143.
//
// Action feature[149] = 143 matrix + 6 scalars:
//   isBomb / isLargest / size==next count / size==prev count
//   / minSteps(after)/16 / validity 1.0.
#pragma once

#include <array>
#include <vector>

#include "paodekuai/cards.h"
#include "paodekuai/moves.h"
#include "paodekuai/public_view.h"

namespace paodekuai {

inline constexpr int kCardRows = 11;
inline constexpr int kCardMat = kCardRows * kRanks;  // 143
inline constexpr int kHistoryLen = 15;
inline constexpr int kLstmSteps = 5;
inline constexpr int kLstmIn = 3 * kCardMat;         // 429
inline constexpr int kStaticMatrices = 8;
inline constexpr int kImpMatrices = kStaticMatrices + kHistoryLen;  // 23
inline constexpr int kImpBin = kImpMatrices * kCardMat;             // 3289
inline constexpr int kHandHot = 16;
inline constexpr int kBombHot = 9;
inline constexpr int kImpScalars = 2 * kHandHot + kBombHot + 3;     // 44
inline constexpr int kImpSize = kImpBin + kImpScalars;              // 3333
inline constexpr int kNodeBin = kStaticMatrices * kCardMat;         // 1144
inline constexpr int kNodeSize = kNodeBin + kImpScalars;            // 1188
inline constexpr int kSeqSize = kHistoryLen * kCardMat;             // 2145
inline constexpr int kActionSize = kCardMat + 6;                    // 149
inline constexpr int kNumActions = 2048;

struct LegalOption {
    int slot = 0;                // 0..n-1 within this decision point
    CardSet concrete;
    std::array<float, kActionSize> feature{};
};

// Writes the generic 11x13 card matrix for any card set.
void cardMatrix(const CardSet &cs, float *out);

// Node + history encoding for the viewing player (must be the active seat).
void encode(const PublicView &view, float *xImp /* kNodeSize */,
            float *seq /* kSeqSize */);

// One option per concrete legal play, engine constraints included.
std::vector<LegalOption> legalOptions(const PublicView &view);

}  // namespace paodekuai

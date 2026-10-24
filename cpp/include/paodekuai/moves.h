// Move type detection, move generation and legal-action filtering for
// Paodekuai, identical to the training side (PerfectPaoCPP pdk/moves.h).
//
// Hunan Paodekuai patterns: solo, pair, bare triple, triple+two loose kickers
// (pair or two different singles), straight (>=5, 3..A), pair chain (>=2),
// bare triple chain (>=2), plane (triple chain + same number of loose solo or
// pair wings), bomb (four of a kind). No rocket, no jokers, no four-plus-two.
//
// Must-beat ("有牌必管"): when following a rival play, pass is legal ONLY if
// no beating move exists (bombs included in the mandatory-beat set).
#pragma once

#include <vector>

#include "paodekuai/cards.h"

namespace paodekuai {

enum MoveType {
    MT_PASS = 0,
    MT_SINGLE = 1,
    MT_PAIR = 2,
    MT_TRIPLE = 3,           // bare three of a kind
    MT_BOMB = 4,             // four of a kind, beats everything non-bomb
    MT_TRIPLE_TWO = 5,       // triple + two loose kickers (exactly 5 cards)
    MT_STRAIGHT = 6,         // chain of solos, >= 5
    MT_PAIR_STRAIGHT = 7,    // chain of pairs, >= 2
    MT_TRIPLE_STRAIGHT = 8,  // chain of bare triples, >= 2
    MT_PLANE_SOLO = 9,       // triple chain + one loose solo per trio
    MT_PLANE_PAIR = 10,      // triple chain + one pair per trio
    MT_WRONG = 11,
};

inline constexpr int kMinStraight = 5;   // minimum solos in a straight
inline constexpr int kMinPairChain = 2;  // minimum pairs in a pair chain
inline constexpr int kMinPlane = 2;      // minimum triples in a triple chain

struct MoveInfo {
    int type = MT_WRONG;
    int rank = -1;  // rank index of the main card (lowest rank for chains)
    int len = 1;    // chain length
};

MoveInfo detectMove(const CardSet &m);

// Generates every combination that can be formed from a hand (used when
// leading). Never contains the pass move.
std::vector<CardSet> genAllMoves(const CardSet &hand);

// Legal moves against `toBeat` under the must-beat rule. When `toBeat` is
// empty the player leads freely (no pass). When following:
//   - if any beating move exists, ONLY beating moves are returned (pass is
//     illegal);
//   - if nothing beats, the single pass move is returned.
std::vector<CardSet> legalMoves(const CardSet &hand, const CardSet &toBeat);

// Whether `m` beats `rival` (assumes both are valid patterns).
bool beats(const CardSet &move, const CardSet &rival);

inline bool isBomb(const CardSet &m) {
    return m.total() == 4 && detectMove(m).type == MT_BOMB;
}

}  // namespace paodekuai

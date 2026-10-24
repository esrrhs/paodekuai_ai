// Card primitives for Paodekuai (Run Fast, Hunan 3-player 16-card rules).
//
// Ranks are indexed 0..12, suit-less, counts only:
//   0=3 1=4 2=5 3=6 4=7 5=8 6=9 7=T 8=J 9=Q 10=K 11=A 12=2
// Deck (48 cards): ranks 3..K four copies each, A three copies, 2 one copy.
// Rank order equals index order (3 < 4 < ... < K < A < 2).
// Chains (straight / pair chain / triple chain) only cover ranks 0..11 (3..A);
// rank 12 (2) never participates in chains.
#pragma once

#include <array>
#include <cstdint>
#include <string>
#include <string_view>

namespace paodekuai {

inline constexpr int kRanks = 13;      // 3..2
inline constexpr int kChainRanks = 12; // 3..A participate in chains
inline constexpr int kDeckSize = 48;   // 54 - jokers - three 2s - one A
inline constexpr int kHandSize = 16;   // 3 players x 16 = 48, no kitty

char rankChar(int rankIndex);
int charRank(char c);

// A multiset of cards represented as counts of the 13 ranks.
// The empty set (all zero) denotes "pass".
struct CardSet {
    std::array<int8_t, kRanks> c{};

    int total() const {
        int s = 0;
        for (int x : c) s += x;
        return s;
    }
    bool empty() const { return total() == 0; }

    void add(int rank, int n = 1) { c[rank] = static_cast<int8_t>(c[rank] + n); }
    void add(const CardSet &o) {
        for (int r = 0; r < kRanks; ++r) c[r] = static_cast<int8_t>(c[r] + o.c[r]);
    }
    void sub(const CardSet &o) {
        for (int r = 0; r < kRanks; ++r) c[r] = static_cast<int8_t>(c[r] - o.c[r]);
    }
    bool contains(const CardSet &o) const {
        for (int r = 0; r < kRanks; ++r)
            if (c[r] < o.c[r]) return false;
        return true;
    }
    void clear() { c.fill(0); }

    bool operator==(const CardSet &o) const { return c == o.c; }
    bool operator!=(const CardSet &o) const { return !(*this == o); }

    std::string str() const;             // e.g. "334455A2" (ascending rank order)
    static CardSet parse(std::string_view s);

    // Stable 64-bit key. Counts are 0..4, so each rank needs 3 bits (13 * 3 = 39).
    uint64_t key() const {
        uint64_t k = 0;
        for (int r = 0; r < kRanks; ++r) k |= uint64_t(c[r]) << (3 * r);
        return k;
    }
};

// Deterministic splitmix64 PRNG (same as the training side).
class Rng {
public:
    explicit Rng(uint64_t seed = 0) { state_ = seed ? seed : 0x9e3779b97f4a7c15ULL; }
    uint64_t nextU64() {
        uint64_t z = (state_ += 0x9e3779b97f4a7c15ULL);
        z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
        z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
        return z ^ (z >> 31);
    }
    // Uniform integer in [0, n).
    int below(int n) { return int(nextU64() % uint64_t(n)); }

private:
    uint64_t state_;
};

// A shuffled 48-card deck as rank indices: 3..K x4, A x3, 2 x1.
std::array<int, kDeckSize> shuffledDeck(Rng &rng);

}  // namespace paodekuai

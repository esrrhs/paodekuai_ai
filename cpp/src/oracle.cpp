#include "paodekuai/oracle.h"

#include <algorithm>
#include <cstring>
#include <functional>
#include <vector>

#include "paodekuai/moves.h"

namespace paodekuai {

namespace {

// ---------------------------------------------------------------------------
// F(a,b,c,d): minimum moves for a hand described by group counts
// (a singles, b pairs, c triples, d bombs). Rank identities are abstracted
// away; chains are NOT considered here, they are handled by the outer DFS.
// ---------------------------------------------------------------------------

constexpr int kMaxA = 17;
constexpr int kMaxB = 9;
constexpr int kMaxC = 6;
constexpr int kMaxD = 5;
int8_t F[kMaxA][kMaxB][kMaxC][kMaxD];

// Enumerate ways to take `need` (<= 2) loose cards from pools of group counts.
// Broken groups downgrade: a used-1 trio leaves a pair, a used-1 bomb leaves a
// trio, etc. Callback receives the new group counts.
template <class Fn>
void enumLooseKickers(int a, int b, int c, int d, int need, Fn&& fn) {
    for (int s = 0; s <= std::min(need, a); ++s) {
        int r1 = need - s;
        if (r1 == 0) { fn(a - s, b, c, d); continue; }
        // Pairs: bp broken pairs and pu cards from them (1..2 each).
        for (int bp = 0; bp <= std::min(b, r1); ++bp) {
            for (int pu = std::max(bp, 0); pu <= std::min(2 * bp, r1); ++pu) {
                int r2 = r1 - pu;
                int pairLeftSingles = 2 * bp - pu;
                if (r2 == 0) {
                    fn(a - s + pairLeftSingles, b - bp, c, d);
                    continue;
                }
                // Trios: bt broken, tu cards (1..3 each).
                for (int bt = 0; bt <= std::min(c, r2); ++bt) {
                    for (int tu = bt; tu <= std::min(3 * bt, r2); ++tu) {
                        int r3 = r2 - tu;
                        for (int t2 = 0; t2 <= bt; ++t2) {
                            for (int t1 = 0; t1 + t2 <= bt; ++t1) {
                                int t3 = bt - t1 - t2;
                                if (t1 + 2 * t2 + 3 * t3 != tu) continue;
                                int r4 = r3;
                                if (r4 == 0) {
                                    fn(a - s + pairLeftSingles + t2,
                                       b - bp + t1, c - bt, d);
                                    continue;
                                }
                                // Bombs: bd broken, bu cards (1..4 each).
                                for (int bd = 0; bd <= std::min(d, r4); ++bd) {
                                    for (int bu = bd; bu <= std::min(4 * bd, r4); ++bu) {
                                        if (bu != r4) continue;
                                        for (int u3 = 0; u3 <= bd; ++u3)
                                            for (int u2 = 0; u2 + u3 <= bd; ++u2)
                                                for (int u1 = 0; u1 + u2 + u3 <= bd; ++u1) {
                                                    int u4 = bd - u1 - u2 - u3;
                                                    if (u1 + 2 * u2 + 3 * u3 + 4 * u4 != bu)
                                                        continue;
                                                    fn(a - s + pairLeftSingles + t2 + u3,
                                                       b - bp + t1 + u2,
                                                       c - bt + u1, d - bd);
                                                }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

bool g_fReady = false;

inline int fGet(int a, int b, int c, int d) {
    if (a < 0 || b < 0 || c < 0 || d < 0) return 100;
    if (a >= kMaxA || b >= kMaxB || c >= kMaxC || d >= kMaxD)
        return a + b + c + d;
    int v = F[a][b][c][d];
    return v < 0 ? a + b + c + d : v;
}

void initFTable() {
    std::memset(F, -1, sizeof(F));
    F[0][0][0][0] = 0;
    for (int total = 1; total <= kHandSize; ++total) {
        for (int a = 0; a < kMaxA; ++a)
            for (int b = 0; b < kMaxB; ++b)
                for (int c = 0; c < kMaxC; ++c)
                    for (int d = 0; d < kMaxD; ++d) {
                        if (a + 2 * b + 3 * c + 4 * d != total) continue;
                        int best = a + b + c + d;
                        if (a >= 1) best = std::min(best, 1 + fGet(a - 1, b, c, d));
                        if (b >= 1) best = std::min(best, 1 + fGet(a, b - 1, c, d));
                        if (c >= 1) best = std::min(best, 1 + fGet(a, b, c - 1, d));
                        if (d >= 1) best = std::min(best, 1 + fGet(a, b, c, d - 1));
                        // triple + two loose kickers
                        if (c >= 1)
                            enumLooseKickers(a, b, c - 1, d, 2,
                                             [&](int na, int nb, int nc, int nd) {
                                                 best = std::min(best, 1 + fGet(na, nb, nc, nd));
                                             });
                        F[a][b][c][d] = static_cast<int8_t>(best);
                    }
    }
    g_fReady = true;
}

int fDP(int a, int b, int c, int d) {
    if (a < 0 || b < 0 || c < 0 || d < 0) return 100;
    if (!g_fReady) initFTable();
    if (a >= kMaxA || b >= kMaxB || c >= kMaxC || d >= kMaxD)
        return a + b + c + d;
    int v = F[a][b][c][d];
    return v < 0 ? a + b + c + d : v;
}

// ---------------------------------------------------------------------------
// Memoized DFS over explicit rank counts (thread-local, direct-mapped cache).
// ---------------------------------------------------------------------------

constexpr size_t kCacheSlots = 65536;
constexpr size_t kCacheMask = kCacheSlots - 1;

struct CacheEntry {
    uint64_t key = 0;
    int8_t val = 0;
};

struct Memo {
    std::vector<CacheEntry> table;
    size_t count = 0;
    Memo() : table(kCacheSlots) {}
};

Memo*& memoPtr() {
    static thread_local Memo* p = nullptr;
    return p;
}

Memo& getMemo() {
    Memo*& p = memoPtr();
    if (!p) p = new Memo;
    return *p;
}

void releaseMemo() {
    Memo*& p = memoPtr();
    delete p;
    p = nullptr;
}

uint64_t packKey(const std::array<int8_t, kRanks>& cnt) {
    uint64_t k = 0;
    for (int r = 0; r < kRanks; ++r) k |= uint64_t(cnt[r]) << (3 * r);
    return k;
}

inline size_t hashKey(uint64_t k) {
    k ^= k >> 33;
    k *= 0xff51afd7ed558ccdULL;
    k ^= k >> 33;
    k *= 0xc4ceb9fe1a85ec53ULL;
    k ^= k >> 33;
    return static_cast<size_t>(k & kCacheMask);
}

// Quick check if any potential straight / pair chain / triple chain exists
// (ranks 0..11 only).
inline bool hasPotentialChain(const std::array<int8_t, kRanks>& cnt) {
    int maxSolo = 0, curSolo = 0;
    int maxPair = 0, curPair = 0;
    int maxTrio = 0, curTrio = 0;
    for (int r = 0; r < kChainRanks; ++r) {
        int c = cnt[r];
        if (c >= 1) { ++curSolo; maxSolo = std::max(maxSolo, curSolo); }
        else curSolo = 0;
        if (c >= 2) { ++curPair; maxPair = std::max(maxPair, curPair); }
        else curPair = 0;
        if (c >= 3) { ++curTrio; maxTrio = std::max(maxTrio, curTrio); }
        else curTrio = 0;
    }
    return maxSolo >= kMinStraight || maxPair >= kMinPairChain ||
           maxTrio >= kMinPlane;
}

// Lower bound ignoring chains.
int lowerBound(const std::array<int8_t, kRanks>& cnt) {
    int a = 0, b = 0, c = 0, d = 0;
    for (int r = 0; r < kRanks; ++r) {
        if (cnt[r] == 1) ++a;
        else if (cnt[r] == 2) ++b;
        else if (cnt[r] == 3) ++c;
        else if (cnt[r] == 4) ++d;
    }
    return fDP(a, b, c, d);
}

// Enumerate every multiset of `need` cards available in cnt.
template <class Fn>
void enumWingMultisets(const std::array<int8_t, kRanks>& cnt, int rank, int need,
                       std::array<int8_t, kRanks>& pick, Fn&& fn) {
    if (need == 0) { fn(pick); return; }
    if (rank >= kRanks) return;
    int mx = std::min<int>(cnt[rank], need);
    for (int take = 0; take <= mx; ++take) {
        pick[rank] = static_cast<int8_t>(take);
        enumWingMultisets(cnt, rank + 1, need - take, pick, fn);
    }
    pick[rank] = 0;
}

int solve(std::array<int8_t, kRanks> cnt) {
    uint64_t key = packKey(cnt);
    if (key == 0) return 0;

    Memo& memo = getMemo();
    size_t h = hashKey(key);
    if (memo.table[h].key == key && memo.table[h].val > 0) {
        return memo.table[h].val;
    }

    // Fast-path: no chain possible -> non-chain DP lower bound is exact.
    if (!hasPotentialChain(cnt)) {
        int ans = lowerBound(cnt);
        if (memo.table[h].key == 0) ++memo.count;
        memo.table[h].key = key;
        memo.table[h].val = static_cast<int8_t>(ans);
        return ans;
    }

    int best = lowerBound(cnt);

    // runs of consecutive ranks (3..A) with enough copies
    auto enumerateChains = [&](int repeat, int minLen, bool withWings) {
        int i = 0;
        while (i < kChainRanks) {
            if (cnt[i] < repeat) { ++i; continue; }
            int j = i;
            while (j + 1 < kChainRanks && cnt[j + 1] >= repeat) ++j;
            int runLen = j - i + 1;
            for (int len = minLen; len <= runLen; ++len) {
                for (int s = i; s + len <= j + 1; ++s) {
                    auto rem = cnt;
                    for (int x = 0; x < len; ++x) rem[s + x] -= repeat;
                    // "<=" (not "<"): the non-chain lower bound can be tight
                    // enough to equal the chained path, so equal estimates must
                    // still be explored. rem always has strictly fewer cards,
                    // so the DFS terminates.
                    if (1 + lowerBound(rem) <= best)
                        best = std::min(best, 1 + solve(rem));
                    if (withWings) {
                        // plane + L loose solo wings
                        if (4 * len <= kHandSize) {
                            std::array<int8_t, kRanks> pick{};
                            enumWingMultisets(rem, 0, len, pick,
                                              [&](const std::array<int8_t, kRanks>& w) {
                                                  auto after = rem;
                                                  for (int r = 0; r < kRanks; ++r)
                                                      after[r] -= w[r];
                                                  if (1 + lowerBound(after) <= best)
                                                      best = std::min(best, 1 + solve(after));
                                              });
                        }
                        // plane + L pair wings (distinct ranks)
                        if (5 * len <= kHandSize) {
                            std::vector<int> pairRanks;
                            for (int r = 0; r < kRanks; ++r)
                                if (rem[r] >= 2) pairRanks.push_back(r);
                            int np = static_cast<int>(pairRanks.size());
                            if (np >= len) {
                                std::vector<int> sel(len);
                                std::function<void(int, int)> combos = [&](int idx, int start) {
                                    if (idx == len) {
                                        auto after = rem;
                                        for (int r : sel) after[r] -= 2;
                                        if (1 + lowerBound(after) <= best)
                                            best = std::min(best, 1 + solve(after));
                                        return;
                                    }
                                    for (int x = start; x + (len - idx - 1) < np; ++x) {
                                        sel[idx] = pairRanks[x];
                                        combos(idx + 1, x + 1);
                                    }
                                };
                                combos(0, 0);
                            }
                        }
                    }
                }
            }
            i = j + 1;
        }
    };
    enumerateChains(1, kMinStraight, false);
    enumerateChains(2, kMinPairChain, false);
    enumerateChains(3, kMinPlane, true);

    if (memo.table[h].key == 0) ++memo.count;
    memo.table[h].key = key;
    memo.table[h].val = static_cast<int8_t>(best);
    return best;
}

}  // namespace

int minSteps(const CardSet& hand) {
    std::array<int8_t, kRanks> cnt{};
    for (int r = 0; r < kRanks; ++r) cnt[r] = hand.c[r];
    return solve(cnt);
}

void clearOracleCache() { releaseMemo(); }

size_t oracleCacheSize() {
    Memo* p = memoPtr();
    return p ? p->count : 0;
}

}  // namespace paodekuai

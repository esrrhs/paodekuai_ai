#include "paodekuai/moves.h"

#include <algorithm>
#include <unordered_set>

namespace paodekuai {

namespace {

// Ranks present in the move (rank index order).
std::vector<int> presentRanks(const CardSet& m) {
    std::vector<int> v;
    for (int r = 0; r < kRanks; ++r)
        if (m.c[r] > 0) v.push_back(r);
    return v;
}

bool consecutive(const std::vector<int>& ranks) {
    for (size_t i = 1; i < ranks.size(); ++i)
        if (ranks[i] - ranks[i - 1] != 1) return false;
    return true;
}

CardSet fromRanks(const std::vector<int>& ranks, int repeat) {
    CardSet m;
    for (int r : ranks) m.add(r, repeat);
    return m;
}

// Enumerates every multiset of `need` loose cards from `cnt`.
template <class Fn>
void enumLooseMultisets(const std::array<int8_t, kRanks>& cnt, int rank,
                        int need, CardSet& pick, Fn&& fn) {
    if (need == 0) {
        fn(pick);
        return;
    }
    if (rank >= kRanks) return;
    int mx = std::min<int>(cnt[rank], need);
    for (int take = 0; take <= mx; ++take) {
        pick.c[rank] = static_cast<int8_t>(take);
        enumLooseMultisets(cnt, rank + 1, need - take, pick, fn);
    }
    pick.c[rank] = 0;
}

// Distinct-rank combinations of `need` ranks from `cands` (ascending order).
template <class Fn>
void enumRankCombos(const std::vector<int>& cands, int idx, int need,
                    std::vector<int>& sel, Fn&& fn) {
    if (need == 0) {
        fn(sel);
        return;
    }
    for (size_t i = idx; i + need <= cands.size(); ++i) {
        sel.push_back(cands[i]);
        enumRankCombos(cands, static_cast<int>(i) + 1, need - 1, sel, fn);
        sel.pop_back();
    }
}

// ---------------------------------------------------------------------------
// Move generator
// ---------------------------------------------------------------------------
class MoveGenerator {
public:
    explicit MoveGenerator(const CardSet& hand) : hand_(hand) {}

    std::vector<CardSet> genAll() {
        std::vector<CardSet> out;
        auto append = [&](std::vector<CardSet> v) {
            for (auto& m : v) out.push_back(std::move(m));
            v.clear();
        };
        append(genSingles());
        append(genPairs());
        append(genTriples());
        append(genBombs());
        append(genTripleTwo());
        append(genSerials(1, kMinStraight));  // straights
        append(genSerials(2, kMinPairChain)); // pair chains
        append(genSerials(3, kMinPlane));     // bare triple chains
        append(genPlaneSolo());
        append(genPlanePair());
        dedupe(out);
        return out;
    }

    // All moves of one category (chains restricted to `len` when len > 0).
    std::vector<CardSet> genByType(int type, int len = 0) {
        std::vector<CardSet> out;
        switch (type) {
            case MT_SINGLE: out = genSingles(); break;
            case MT_PAIR: out = genPairs(); break;
            case MT_TRIPLE: out = genTriples(); break;
            case MT_BOMB: out = genBombs(); break;
            case MT_TRIPLE_TWO: out = genTripleTwo(); break;
            case MT_STRAIGHT: out = genSerials(1, kMinStraight, len); break;
            case MT_PAIR_STRAIGHT: out = genSerials(2, kMinPairChain, len); break;
            case MT_TRIPLE_STRAIGHT: out = genSerials(3, kMinPlane, len); break;
            case MT_PLANE_SOLO: out = genPlaneSolo(len); break;
            case MT_PLANE_PAIR: out = genPlanePair(len); break;
            default: break;
        }
        dedupe(out);
        return out;
    }

private:
    CardSet hand_;

    static void dedupe(std::vector<CardSet>& moves) {
        std::unordered_set<uint64_t> seen;
        seen.reserve(moves.size() * 2);
        size_t w = 0;
        for (auto& m : moves) {
            if (seen.insert(m.key()).second) moves[w++] = std::move(m);
        }
        moves.resize(w);
    }

    std::vector<CardSet> genSingles() const {
        std::vector<CardSet> r;
        for (int rnk = 0; rnk < kRanks; ++rnk)
            if (hand_.c[rnk] >= 1) {
                CardSet m;
                m.add(rnk);
                r.push_back(m);
            }
        return r;
    }
    std::vector<CardSet> genPairs() const {
        std::vector<CardSet> r;
        for (int rnk = 0; rnk < kRanks; ++rnk)
            if (hand_.c[rnk] >= 2) {
                CardSet m;
                m.add(rnk, 2);
                r.push_back(m);
            }
        return r;
    }
    std::vector<CardSet> genTriples() const {
        std::vector<CardSet> r;
        for (int rnk = 0; rnk < kRanks; ++rnk)
            if (hand_.c[rnk] >= 3) {
                CardSet m;
                m.add(rnk, 3);
                r.push_back(m);
            }
        return r;
    }
    std::vector<CardSet> genBombs() const {
        std::vector<CardSet> r;
        for (int rnk = 0; rnk < kRanks; ++rnk)
            if (hand_.c[rnk] == 4) {
                CardSet m;
                m.add(rnk, 4);
                r.push_back(m);
            }
        return r;
    }

    // Triple + two loose kickers (a pair or two different singles; kickers
    // never use the triple's own rank, so 3333 is only ever a bomb).
    std::vector<CardSet> genTripleTwo() const {
        std::vector<CardSet> r;
        for (int t = 0; t < kRanks; ++t) {
            if (hand_.c[t] < 3) continue;
            auto rest = hand_.c;
            rest[t] = 0;
            CardSet pick;
            enumLooseMultisets(rest, 0, 2, pick, [&](const CardSet& w) {
                CardSet m;
                m.add(t, 3);
                for (int rr = 0; rr < kRanks; ++rr)
                    if (w.c[rr]) m.add(rr, w.c[rr]);
                r.push_back(m);
            });
        }
        return r;
    }

    // repeat = 1 solos / 2 pairs / 3 triples; chains span ranks 0..11 only.
    std::vector<CardSet> genSerials(int repeat, int minLen, int fixedLen = 0) const {
        std::vector<int> ranks;
        for (int r = 0; r < kChainRanks; ++r)
            if (hand_.c[r] >= repeat) ranks.push_back(r);
        std::vector<std::pair<int, int>> runs;  // (start index in ranks, length)
        for (size_t i = 0; i < ranks.size();) {
            size_t j = i;
            while (j + 1 < ranks.size() && ranks[j + 1] - ranks[j] == 1) ++j;
            runs.emplace_back(static_cast<int>(i), static_cast<int>(j - i + 1));
            i = j + 1;
        }
        std::vector<CardSet> out;
        for (auto [start, longest] : runs) {
            if (fixedLen > 0) {
                if (longest < fixedLen) continue;
                for (int idx = 0; idx + fixedLen <= longest; ++idx) {
                    std::vector<int> chain(ranks.begin() + start + idx,
                                           ranks.begin() + start + idx + fixedLen);
                    out.push_back(fromRanks(chain, repeat));
                }
            } else {
                for (int len = minLen; len <= longest; ++len)
                    for (int idx = 0; idx + len <= longest; ++idx) {
                        std::vector<int> chain(ranks.begin() + start + idx,
                                               ranks.begin() + start + idx + len);
                        out.push_back(fromRanks(chain, repeat));
                    }
            }
        }
        return out;
    }

    std::vector<CardSet> tripleChains(int fixedLen) const {
        return genSerials(3, kMinPlane, fixedLen);
    }

    std::vector<CardSet> genPlaneSolo(int fixedLen = 0) const {
        std::vector<CardSet> planes = tripleChains(fixedLen);
        std::vector<CardSet> out;
        for (const CardSet& pl : planes) {
            int chainLen = 0;
            auto rest = hand_.c;
            for (int r = 0; r < kRanks; ++r) {
                if (pl.c[r] == 3) {
                    ++chainLen;
                    rest[r] = static_cast<int8_t>(rest[r] - 3);
                }
            }
            CardSet pick;
            enumLooseMultisets(rest, 0, chainLen, pick, [&](const CardSet& w) {
                CardSet m = pl;
                for (int r = 0; r < kRanks; ++r)
                    if (w.c[r]) m.add(r, w.c[r]);
                out.push_back(m);
            });
        }
        return out;
    }

    std::vector<CardSet> genPlanePair(int fixedLen = 0) const {
        std::vector<CardSet> planes = tripleChains(fixedLen);
        std::vector<CardSet> out;
        for (const CardSet& pl : planes) {
            int chainLen = 0;
            std::vector<int> pairCands;
            for (int r = 0; r < kRanks; ++r) {
                if (pl.c[r] == 3) {
                    ++chainLen;
                    continue;
                }
                if (hand_.c[r] >= 2) pairCands.push_back(r);
            }
            std::vector<int> sel;
            enumRankCombos(pairCands, 0, chainLen, sel, [&](const std::vector<int>& ps) {
                CardSet m = pl;
                for (int r : ps) m.add(r, 2);
                out.push_back(m);
            });
        }
        return out;
    }
};

}  // namespace

MoveInfo detectMove(const CardSet& m) {
    MoveInfo info;
    int size = m.total();

    if (size == 0) {
        info.type = MT_PASS;
        return info;
    }
    if (size == 1) {
        for (int r = 0; r < kRanks; ++r)
            if (m.c[r]) {
                info.type = MT_SINGLE;
                info.rank = r;
                return info;
            }
    }
    if (size == 2) {
        for (int r = 0; r < kRanks; ++r)
            if (m.c[r] == 2) {
                info.type = MT_PAIR;
                info.rank = r;
                return info;
            }
        return info;  // WRONG
    }
    if (size == 3) {
        for (int r = 0; r < kRanks; ++r)
            if (m.c[r] == 3) {
                info.type = MT_TRIPLE;
                info.rank = r;
                return info;
            }
        return info;
    }
    if (size == 4) {
        for (int r = 0; r < kRanks; ++r)
            if (m.c[r] == 4) {
                info.type = MT_BOMB;
                info.rank = r;
                return info;
            }
        // pair chain of two (e.g. 3344) is only four cards
        std::vector<int> pr4 = presentRanks(m);
        if (pr4.size() == 2 && m.c[pr4[0]] == 2 && m.c[pr4[1]] == 2 &&
            pr4[1] - pr4[0] == 1 && pr4[1] < kChainRanks) {
            info.type = MT_PAIR_STRAIGHT;
            info.rank = pr4[0];
            info.len = 2;
            return info;
        }
        return info;
    }
    if (size == 5) {
        // straight of five consecutive distinct ranks within 3..A
        std::vector<int> pr = presentRanks(m);
        if (static_cast<int>(pr.size()) == 5 && pr.back() < kChainRanks &&
            consecutive(pr)) {
            info.type = MT_STRAIGHT;
            info.rank = pr.front();
            info.len = 5;
            return info;
        }
        // triple + two loose kickers (pair or two singles)
        int triRank = -1;
        bool bad = false;
        for (int r = 0; r < kRanks; ++r) {
            if (m.c[r] == 3) {
                if (triRank >= 0) { bad = true; break; }
                triRank = r;
            } else if (m.c[r] != 0 && m.c[r] != 1 && m.c[r] != 2) {
                bad = true;
                break;
            }
        }
        if (!bad && triRank >= 0) {
            info.type = MT_TRIPLE_TWO;
            info.rank = triRank;
            return info;
        }
        return info;
    }

    // size >= 6
    std::vector<int> pr = presentRanks(m);

    // straight: all distinct, consecutive, within 3..A
    if (static_cast<int>(pr.size()) == size && pr.back() < kChainRanks &&
        consecutive(pr)) {
        info.type = MT_STRAIGHT;
        info.rank = pr.front();
        info.len = size;
        return info;
    }
    // pair chain
    bool allPairs = true;
    for (int r : pr)
        if (m.c[r] != 2) { allPairs = false; break; }
    if (allPairs && pr.back() < kChainRanks && consecutive(pr)) {
        info.type = MT_PAIR_STRAIGHT;
        info.rank = pr.front();
        info.len = static_cast<int>(pr.size());
        return info;
    }
    // bare triple chain
    bool allTriples = true;
    for (int r : pr)
        if (m.c[r] != 3) { allTriples = false; break; }
    if (allTriples && pr.back() < kChainRanks && consecutive(pr)) {
        info.type = MT_TRIPLE_STRAIGHT;
        info.rank = pr.front();
        info.len = static_cast<int>(pr.size());
        return info;
    }

    // planes: consecutive ranks with count >= 3 form the chain; leftover cards
    // are wings (a rank with count 4 contributes 3 to the chain, 1 wing).
    // Every sub-chain of each >=3 run is examined, longest first.
    for (int start = 0; start < kChainRanks;) {
        if (m.c[start] < 3) {
            ++start;
            continue;
        }
        int end = start;
        while (end + 1 < kChainRanks && m.c[end + 1] >= 3) ++end;
        for (int L = end - start + 1; L >= kMinPlane; --L) {
            for (int s = start; s + L <= end + 1; ++s) {
                CardSet wings = m;
                for (int r = s; r < s + L; ++r)
                    wings.c[r] = static_cast<int8_t>(wings.c[r] - 3);
                int wTotal = wings.total();
                if (wTotal == L) {
                    info.type = MT_PLANE_SOLO;
                    info.rank = s;
                    info.len = L;
                    return info;
                }
                if (wTotal == 2 * L) {
                    bool pairsOnly = true;
                    for (int r = 0; r < kRanks; ++r)
                        if (wings.c[r] != 0 && wings.c[r] != 2) {
                            pairsOnly = false;
                            break;
                        }
                    if (pairsOnly) {
                        info.type = MT_PLANE_PAIR;
                        info.rank = s;
                        info.len = L;
                        return info;
                    }
                }
            }
        }
        start = end + 1;
    }
    return info;  // WRONG
}

bool beats(const CardSet& move, const CardSet& rival) {
    MoveInfo a = detectMove(move);
    MoveInfo b = detectMove(rival);
    if (a.type == MT_WRONG || b.type == MT_WRONG) return false;
    if (a.type == MT_BOMB && b.type != MT_BOMB) return true;
    if (a.type == MT_BOMB && b.type == MT_BOMB) return a.rank > b.rank;
    if (a.type != b.type) return false;
    if (a.type == MT_PLANE_SOLO || a.type == MT_PLANE_PAIR ||
        a.type == MT_STRAIGHT || a.type == MT_PAIR_STRAIGHT ||
        a.type == MT_TRIPLE_STRAIGHT) {
        return a.len == b.len && a.rank > b.rank;
    }
    return a.rank > b.rank;  // single / pair / triple / triple+two
}

std::vector<CardSet> genAllMoves(const CardSet& hand) {
    return MoveGenerator(hand).genAll();
}

std::vector<CardSet> legalMoves(const CardSet& hand, const CardSet& toBeat) {
    if (toBeat.empty()) return genAllMoves(hand);  // free lead, no pass
    MoveInfo rival = detectMove(toBeat);
    if (rival.type == MT_WRONG) return genAllMoves(hand);
    MoveGenerator gen(hand);
    std::vector<CardSet> moves;
    if (rival.type == MT_BOMB) {
        for (CardSet& m : gen.genByType(MT_BOMB))
            if (beats(m, toBeat)) moves.push_back(std::move(m));
    } else {
        for (CardSet& m : gen.genByType(rival.type, rival.len))
            if (beats(m, toBeat)) moves.push_back(std::move(m));
        // bombs beat every ordinary category (must-beat includes bombs)
        for (CardSet& m : gen.genByType(MT_BOMB))
            moves.push_back(std::move(m));
    }
    if (moves.empty()) moves.push_back(CardSet{});  // nothing beats: pass
    return moves;
}

}  // namespace paodekuai

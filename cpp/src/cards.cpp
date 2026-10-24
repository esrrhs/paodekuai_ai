#include "paodekuai/cards.h"

#include <algorithm>

namespace paodekuai {

namespace {
constexpr char kChars[kRanks] = {'3', '4', '5', '6', '7', '8', '9', 'T',
                                 'J', 'Q', 'K', 'A', '2'};
}  // namespace

char rankChar(int rankIndex) { return kChars[rankIndex]; }

int charRank(char ch) {
    for (int r = 0; r < kRanks; ++r)
        if (kChars[r] == ch) return r;
    return -1;
}

std::string CardSet::str() const {
    std::string s;
    for (int r = 0; r < kRanks; ++r)
        s.append(std::string(c[r], kChars[r]));
    return s;
}

CardSet CardSet::parse(std::string_view s) {
    CardSet m;
    for (char ch : s) {
        int r = charRank(ch);
        if (r >= 0) m.c[r]++;
    }
    return m;
}

std::array<int, kDeckSize> shuffledDeck(Rng& rng) {
    std::array<int, kDeckSize> deck;
    int i = 0;
    for (int r = 0; r <= 10; ++r)  // 3..K, four copies
        for (int k = 0; k < 4; ++k) deck[i++] = r;
    for (int k = 0; k < 3; ++k) deck[i++] = 11;  // A x3
    deck[i++] = 12;                               // 2 x1
    // Fisher-Yates
    for (int j = kDeckSize - 1; j > 0; --j) {
        int k = rng.below(j + 1);
        std::swap(deck[j], deck[k]);
    }
    return deck;
}

}  // namespace paodekuai

#include "paodekuai/game_state.h"

#include <algorithm>

#include "paodekuai/public_view.h"

namespace paodekuai {

GameState GameState::deal(Rng &rng) {
    GameState g;
    auto deck = shuffledDeck(rng);
    for (int s = 0; s < 3; ++s)
        for (int i = s * kHandSize; i < (s + 1) * kHandSize; ++i)
            g.hand[s].add(deck[i]);
    // Designate one of the four 3s uniformly; its holder leads.
    int pick = rng.below(4);
    int acc = 0;
    for (int s = 0; s < 3; ++s) {
        acc += g.hand[s].c[0];
        if (pick < acc) {
            g.turn = s;
            break;
        }
    }
    g.lastPlayer = g.turn;
    return g;
}

CardSet GameState::toBeat() const {
    if (seq.empty()) return CardSet{};
    if (!seq.back().cards.empty()) return seq.back().cards;
    // The last action was a pass: either two consecutive passes (free lead)
    // or the action before the pass must be beaten.
    if (seq.size() >= 2 && seq[seq.size() - 2].cards.empty()) return CardSet{};
    if (seq.size() >= 2) return seq[seq.size() - 2].cards;
    return CardSet{};
}

std::vector<CardSet> GameState::legal() const {
    if (over) return {};
    CardSet tb = toBeat();
    bool firstMove = seq.empty();
    if (tb.empty()) {
        // free lead: any combination, pass never legal
        std::vector<CardSet> ms = genAllMoves(hand[turn]);
        if (firstMove) {
            // the first hand must contain a 3
            size_t w = 0;
            for (auto &m : ms)
                if (m.c[0] >= 1) ms[w++] = std::move(m);
            ms.resize(w);
            return ms;
        }
        if (reported(nextSeat())) {
            // leading a solo while the next player reported: must top with
            // the largest solo in hand
            int largest = -1;
            for (int r = 0; r < kRanks; ++r)
                if (hand[turn].c[r] >= 1) largest = r;
            size_t w = 0;
            for (auto &m : ms) {
                MoveInfo info = detectMove(m);
                if (info.type != MT_SINGLE || info.rank == largest)
                    ms[w++] = std::move(m);
            }
            ms.resize(w);
            return ms;
        }
        return ms;
    }
    return legalMoves(hand[turn], tb);  // must-beat semantics inside
}

void GameState::step(const CardSet &m) {
    if (over) return;
    if (!m.empty()) {
        lastPlayer = turn;
        if (detectMove(m).type == MT_BOMB) ++bombCount;
        hand[turn].sub(m);
        for (int r = 0; r < kRanks; ++r) played[turn].add(r, m.c[r]);
        if (hand[turn].total() == 0) {
            over = true;
            winner = turn;
        }
    }
    lastMove[turn] = m;
    seq.push_back(MoveRecord{turn, m});
    if (!over) turn = (turn + 1) % 3;
}

double GameState::payoff(int seat) const {
    if (!over) return 0.0;
    auto payment = [&](int s) -> double {
        if (s == winner) return 0.0;
        int left = hand[s].total();
        int mult = (left == kHandSize) ? 2 : 1;  // never played a card: double
        return double(left * mult);
    };
    if (seat == winner) {
        double sum = 0.0;
        for (int s = 0; s < 3; ++s) sum += payment(s);
        return sum / kHandSize;
    }
    return -payment(seat) / kHandSize;
}

PublicView GameState::getPublicView(int seat) const {
    PublicView view;
    view.viewingPlayerId = seat;
    view.myHand = hand[seat];
    for (int s = 0; s < 3; ++s) view.cardCounts[s] = hand[s].total();
    view.history = seq;
    view.lastMove = lastMove;
    view.toBeat = toBeat();
    view.lastPlayerId = lastPlayer;
    view.activePlayerId = turn;
    view.bombCount = bombCount;
    return view;
}

}  // namespace paodekuai

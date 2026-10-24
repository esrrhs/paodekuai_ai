// The public observation a player (and the policy network) is allowed to see.
// Never exposes another seat's private hand.
#pragma once

#include <array>
#include <vector>

#include "paodekuai/cards.h"

namespace paodekuai {

// One history entry with its seat attribution (passes carry an empty CardSet).
struct MoveRecord {
    int playerId = 0;
    CardSet cards;
};

class PublicView {
public:
    int viewingPlayerId = 0;
    CardSet myHand;
    std::array<int, 3> cardCounts{};     // remaining cards per seat
    std::vector<MoveRecord> history;     // full action history including passes
    std::array<CardSet, 3> lastMove{};   // last move per seat (empty on pass)
    CardSet toBeat{};                    // empty = free lead
    int lastPlayerId = 0;
    int activePlayerId = 0;
    int bombCount = 0;

    int prevSeat() const { return (viewingPlayerId + 2) % 3; }
    int nextSeat() const { return (viewingPlayerId + 1) % 3; }
};

}  // namespace paodekuai

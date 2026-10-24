// Minimum number of moves to play out a hand, used by the action features.
// Port of the training side pdk/oracle.h.
#pragma once

#include <cstddef>

#include "paodekuai/cards.h"

namespace paodekuai {

// Minimum play-out steps for the hand. Results are memoized per thread.
int minSteps(const CardSet &hand);

// Clears the thread-local memoization table.
void clearOracleCache();

}  // namespace paodekuai

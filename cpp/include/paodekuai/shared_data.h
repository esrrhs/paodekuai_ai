// Locates the shared data/ directory (weights, golden fixture) that lives
// next to java/ cpp/ go/ in the repository root.
#pragma once

#include <string>

namespace paodekuai {

// Walks up at most 3 parents from the cwd looking for `relative`.
// Returns "" when not found.
std::string FindSharedData(const std::string &relative);

// FindSharedData, falling back to the cwd-interpreted path so error messages
// carry the file name.
std::string ResolveSharedData(const std::string &relative);

}  // namespace paodekuai

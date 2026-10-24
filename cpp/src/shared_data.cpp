#include "paodekuai/shared_data.h"

#include <filesystem>

namespace paodekuai {

namespace fs = std::filesystem;

std::string FindSharedData(const std::string &relative) {
    std::error_code ec;
    fs::path dir = fs::current_path(ec);
    if (ec) return "";
    for (int i = 0; i <= 3; ++i) {
        fs::path candidate = dir / relative;
        if (fs::exists(candidate, ec)) return candidate.string();
        if (!dir.has_parent_path()) break;
        dir = dir.parent_path();
    }
    return "";
}

std::string ResolveSharedData(const std::string &relative) {
    std::string found = FindSharedData(relative);
    if (!found.empty()) return found;
    std::error_code ec;
    return (fs::current_path(ec) / relative).string();
}

}  // namespace paodekuai

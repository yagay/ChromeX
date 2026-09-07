#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace chromex {

struct ExecSegment {
    uintptr_t start = 0;
    size_t size = 0;
};

struct ChromeElfInfo {
    uintptr_t load_bias = 0;
    std::string path;
    std::string build_id;
    std::vector<ExecSegment> executable_segments;

    bool valid() const {
        return load_bias != 0 && !executable_segments.empty();
    }
};

ChromeElfInfo ResolveLibChrome();

}  // namespace chromex

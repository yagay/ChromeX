#include "arm64_signature_scanner.h"

#include <cstring>

namespace chromex {

std::vector<uintptr_t> FindExactArm64Signature(
        const ChromeElfInfo& elf,
        const uint32_t* words,
        size_t word_count) {
    std::vector<uintptr_t> hits;
    if (!elf.valid() || words == nullptr || word_count == 0) return hits;

    const size_t bytes = word_count * sizeof(uint32_t);
    for (const ExecSegment& segment : elf.executable_segments) {
        if (segment.start == 0 || segment.size < bytes) continue;
        const auto* begin = reinterpret_cast<const uint8_t*>(segment.start);
        const size_t max_offset = segment.size - bytes;
        for (size_t offset = 0; offset <= max_offset; offset += sizeof(uint32_t)) {
            if (std::memcmp(begin + offset, words, bytes) == 0) {
                hits.push_back(segment.start + offset);
            }
        }
    }
    return hits;
}

}  // namespace chromex

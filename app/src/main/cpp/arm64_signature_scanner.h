#pragma once

#include "libchrome_resolver.h"

#include <cstddef>
#include <cstdint>
#include <vector>

namespace chromex {

std::vector<uintptr_t> FindExactArm64Signature(
        const ChromeElfInfo& elf,
        const uint32_t* words,
        size_t word_count);

}  // namespace chromex

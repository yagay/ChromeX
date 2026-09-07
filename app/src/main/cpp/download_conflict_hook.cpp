#include "download_conflict_hook.h"

#include "arm64_signature_scanner.h"
#include "libchrome_resolver.h"

#include <android/log.h>
#include <sys/mman.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <cerrno>
#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <sstream>

namespace chromex {
namespace {

constexpr const char* kTag = "ChromeXNative";
constexpr const char* kChrome152Version = "152.0.7977.75";
constexpr const char* kChrome152BuildId = "18cabf310a758228af3c1a92e641defed1f8e1b8";

// Chrome 152.0.7977.75 arm64-v8a
// download::DownloadPathReservationTracker::GetReservedPath(...)
// libchrome.so file offset 0x307EEA4. The sequence below is the complete prologue up to the first
// BL and is unique in the supplied libchrome.so. Instruction 13 is `mov w22, w5`, where w5 is
// FilenameConflictAction according to Chromium's public function signature.
constexpr std::array<uint32_t, 19> kChrome152GetReservedPath = {
        0xd503233f,  // paciasp
        0xd105c3ff,  // sub sp, sp, #0x170
        0xa9117bfd,
        0xa9126ffc,
        0xa91367fa,
        0xa9145ff8,
        0xa91557f6,
        0xa9164ff4,
        0x910443fd,
        0xaa0003f5,  // mov x21, x0
        0x52800800,  // mov w0, #0x40
        0xaa0703f8,  // mov x24, x7
        0x2a0503f6,  // mov w22, w5  <-- FilenameConflictAction
        0x2a0403fb,  // mov w27, w4
        0xaa0303f7,
        0xaa0203f9,
        0xaa0103fa,
        0xf90003e6,
        0xf81e03a6,
};

constexpr size_t kConflictInstructionIndex = 12;
constexpr uint32_t kExpectedConflictInstruction = 0x2a0503f6;  // mov w22, w5
constexpr uint32_t kForceOverwriteInstruction = 0x52800036;     // mov w22, #1 (OVERWRITE)

std::atomic<bool> g_active{false};

std::string OffsetText(uintptr_t absolute, uintptr_t bias) {
    char buffer[64];
    std::snprintf(buffer, sizeof(buffer), "0x%" PRIxPTR,
            absolute >= bias ? absolute - bias : absolute);
    return buffer;
}

bool PatchInstruction(uintptr_t address, uint32_t expected, uint32_t replacement,
                      std::string* error) {
    auto* instruction = reinterpret_cast<volatile uint32_t*>(address);
    const uint32_t current = *instruction;
    if (current == replacement) return true;
    if (current != expected) {
        if (error != nullptr) {
            std::ostringstream detail;
            detail << "instruction-mismatch current=0x" << std::hex << current
                   << " expected=0x" << expected;
            *error = detail.str();
        }
        return false;
    }

    const long page_size_raw = sysconf(_SC_PAGESIZE);
    const size_t page_size = page_size_raw > 0 ? static_cast<size_t>(page_size_raw) : 4096U;
    const uintptr_t page = address & ~(static_cast<uintptr_t>(page_size) - 1U);

    // This is an already executable private mapping in Chrome's own process. Temporarily add write
    // permission, patch one verified ARM64 instruction, flush I-cache, then restore RX.
    if (mprotect(reinterpret_cast<void*>(page), page_size,
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        if (error != nullptr) {
            std::ostringstream detail;
            detail << "mprotect-rwx errno=" << errno << ':' << std::strerror(errno);
            *error = detail.str();
        }
        return false;
    }

    *instruction = replacement;
    __builtin___clear_cache(reinterpret_cast<char*>(address),
                            reinterpret_cast<char*>(address + sizeof(uint32_t)));

    const bool restored = mprotect(reinterpret_cast<void*>(page), page_size,
                                   PROT_READ | PROT_EXEC) == 0;
    if (!restored) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "failed to restore RX after conflict patch errno=%d:%s",
                            errno, std::strerror(errno));
    }

    const uint32_t verify = *instruction;
    if (verify != replacement) {
        if (error != nullptr) {
            std::ostringstream detail;
            detail << "patch-verify-failed current=0x" << std::hex << verify;
            *error = detail.str();
        }
        return false;
    }
    return true;
}

}  // namespace

HookResult InstallDownloadConflictHook(const char* chrome_version) {
    HookResult out;
    if (g_active.load(std::memory_order_acquire)) {
        out.active = true;
        out.detail = "already-active";
        return out;
    }

    ChromeElfInfo elf = ResolveLibChrome();
    if (!elf.valid()) {
        out.detail = "libchrome.so not loaded";
        return out;
    }

    const std::string version = chrome_version == nullptr ? "" : chrome_version;
    const bool exact_version = version == kChrome152Version;
    const bool exact_build = elf.build_id == kChrome152BuildId;
    if (!exact_version && !exact_build) {
        std::ostringstream detail;
        detail << "unsupported-build version=" << version
               << " buildId=" << (elf.build_id.empty() ? "unknown" : elf.build_id);
        out.detail = detail.str();
        return out;
    }

    const auto hits = FindExactArm64Signature(
            elf, kChrome152GetReservedPath.data(), kChrome152GetReservedPath.size());
    if (hits.size() != 1) {
        std::ostringstream detail;
        detail << "signature-match-count=" << hits.size()
               << " buildId=" << elf.build_id;
        out.detail = detail.str();
        return out;
    }

    const uintptr_t function = hits.front();
    const uintptr_t patch_address = function + kConflictInstructionIndex * sizeof(uint32_t);
    std::string patch_error;
    if (!PatchInstruction(patch_address, kExpectedConflictInstruction,
                          kForceOverwriteInstruction, &patch_error)) {
        std::ostringstream detail;
        detail << "patch-failed=" << patch_error
               << " functionOffset=" << OffsetText(function, elf.load_bias)
               << " patchOffset=" << OffsetText(patch_address, elf.load_bias);
        out.detail = detail.str();
        return out;
    }

    g_active.store(true, std::memory_order_release);

    std::ostringstream detail;
    detail << "ACTIVE-DIRECT-PATCH version=" << version
           << " buildId=" << elf.build_id
           << " functionOffset=" << OffsetText(function, elf.load_bias)
           << " patchOffset=" << OffsetText(patch_address, elf.load_bias)
           << " instruction=mov-w22-1 policy=OVERWRITE";
    out.active = true;
    out.detail = detail.str();
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", out.detail.c_str());
    return out;
}

}  // namespace chromex

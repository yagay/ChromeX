#include "download_conflict_hook.h"

#include "arm64_signature_scanner.h"
#include "libchrome_resolver.h"

#include <android/log.h>
#include <shadowhook.h>

#include <array>
#include <atomic>
#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <sstream>

namespace chromex {
namespace {

constexpr const char* kTag = "ChromeXNative";
constexpr const char* kChrome152Version = "152.0.7977.75";
constexpr const char* kChrome152BuildId = "18cabf310a758228af3c1a92e641defed1f8e1b8";
constexpr int kConflictOverwrite = 1;

// Chrome 152.0.7977.75 arm64-v8a
// download::DownloadPathReservationTracker::GetReservedPath(...)
// libchrome.so file offset 0x307EEA4. The sequence below is the complete prologue up to the first
// BL and is unique in the supplied libchrome.so. It also proves w5 is the conflict action because
// instruction 13 is `mov w22, w5`, matching Chromium's public function signature.
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

using GetReservedPathFn = void (*)(
        void* download_item,
        const void* target_path,
        const void* default_path,
        const void* fallback_directory,
        bool create_directory,
        int conflict_action,
        void* callback,
        const void* containment_directory);

std::atomic<GetReservedPathFn> g_original{nullptr};
std::atomic<void*> g_stub{nullptr};

void GetReservedPathProxy(
        void* download_item,
        const void* target_path,
        const void* default_path,
        const void* fallback_directory,
        bool create_directory,
        int conflict_action,
        void* callback,
        const void* containment_directory) {
    GetReservedPathFn original = g_original.load(std::memory_order_acquire);
    if (original == nullptr) return;

    if (conflict_action != kConflictOverwrite) {
        __android_log_print(ANDROID_LOG_INFO, kTag,
                "forcing FilenameConflictAction %d -> OVERWRITE", conflict_action);
        conflict_action = kConflictOverwrite;
    }
    original(download_item, target_path, default_path, fallback_directory,
            create_directory, conflict_action, callback, containment_directory);
}

std::string OffsetText(uintptr_t absolute, uintptr_t bias) {
    char buffer[64];
    std::snprintf(buffer, sizeof(buffer), "0x%" PRIxPTR,
            absolute >= bias ? absolute - bias : absolute);
    return buffer;
}

}  // namespace

HookResult InstallDownloadConflictHook(const char* chrome_version) {
    HookResult out;
    if (g_stub.load(std::memory_order_acquire) != nullptr) {
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

    const uintptr_t target = hits.front();
    const int init_result = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (init_result != 0) {
        std::ostringstream detail;
        detail << "shadowhook-init=" << init_result << ':'
               << shadowhook_to_errmsg(init_result);
        out.detail = detail.str();
        return out;
    }

    void* original = nullptr;
    void* stub = shadowhook_hook_func_addr_2(
            reinterpret_cast<void*>(target),
            reinterpret_cast<void*>(GetReservedPathProxy),
            &original,
            SHADOWHOOK_HOOK_WITH_UNIQUE_MODE | SHADOWHOOK_HOOK_RECORD,
            "libchrome.so",
            "DownloadPathReservationTracker::GetReservedPath");
    if (stub == nullptr || original == nullptr) {
        const int error = shadowhook_get_errno();
        std::ostringstream detail;
        detail << "hook-failed=" << error << ':' << shadowhook_to_errmsg(error)
               << " offset=" << OffsetText(target, elf.load_bias);
        out.detail = detail.str();
        return out;
    }

    g_original.store(reinterpret_cast<GetReservedPathFn>(original), std::memory_order_release);
    g_stub.store(stub, std::memory_order_release);

    std::ostringstream detail;
    detail << "ACTIVE version=" << version
           << " buildId=" << elf.build_id
           << " offset=" << OffsetText(target, elf.load_bias)
           << " policy=OVERWRITE";
    out.active = true;
    out.detail = detail.str();
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", out.detail.c_str());
    return out;
}

}  // namespace chromex

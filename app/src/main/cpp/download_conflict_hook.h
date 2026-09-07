#pragma once

#include <string>

namespace chromex {

struct HookResult {
    bool active = false;
    std::string detail;
};

HookResult InstallDownloadConflictHook(const char* chrome_version);

}  // namespace chromex

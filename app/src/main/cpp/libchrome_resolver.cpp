#include "libchrome_resolver.h"

#include <elf.h>
#include <link.h>
#include <cstring>
#include <iomanip>
#include <sstream>

namespace chromex {
namespace {

size_t Align4(size_t value) {
    return (value + 3u) & ~size_t{3u};
}

std::string Hex(const uint8_t* data, size_t size) {
    std::ostringstream out;
    out << std::hex << std::setfill('0');
    for (size_t i = 0; i < size; ++i) out << std::setw(2) << static_cast<unsigned>(data[i]);
    return out.str();
}

void ReadBuildId(const dl_phdr_info* info, ChromeElfInfo* out) {
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)& ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_NOTE || ph.p_memsz < sizeof(ElfW(Nhdr))) continue;
        const uint8_t* begin = reinterpret_cast<const uint8_t*>(info->dlpi_addr + ph.p_vaddr);
        const uint8_t* end = begin + ph.p_memsz;
        const uint8_t* cursor = begin;
        while (cursor + sizeof(ElfW(Nhdr)) <= end) {
            const auto* note = reinterpret_cast<const ElfW(Nhdr)*>(cursor);
            cursor += sizeof(ElfW(Nhdr));
            if (cursor + Align4(note->n_namesz) > end) break;
            const char* name = reinterpret_cast<const char*>(cursor);
            cursor += Align4(note->n_namesz);
            if (cursor + Align4(note->n_descsz) > end) break;
            const uint8_t* desc = cursor;
            cursor += Align4(note->n_descsz);
            if (note->n_type == NT_GNU_BUILD_ID && note->n_namesz >= 3
                    && std::memcmp(name, "GNU", 3) == 0) {
                out->build_id = Hex(desc, note->n_descsz);
                return;
            }
        }
    }
}

int Callback(dl_phdr_info* info, size_t, void* opaque) {
    if (info == nullptr || opaque == nullptr || info->dlpi_name == nullptr) return 0;
    const char* name = info->dlpi_name;
    if (std::strstr(name, "libchrome.so") == nullptr) return 0;

    auto* out = static_cast<ChromeElfInfo*>(opaque);
    out->load_bias = static_cast<uintptr_t>(info->dlpi_addr);
    out->path = name;
    out->executable_segments.clear();
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)& ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_LOAD || (ph.p_flags & PF_X) == 0 || ph.p_memsz == 0) continue;
        out->executable_segments.push_back({
                static_cast<uintptr_t>(info->dlpi_addr + ph.p_vaddr),
                static_cast<size_t>(ph.p_memsz)});
    }
    ReadBuildId(info, out);
    return 1;
}

}  // namespace

ChromeElfInfo ResolveLibChrome() {
    ChromeElfInfo out;
    dl_iterate_phdr(Callback, &out);
    return out;
}

}  // namespace chromex

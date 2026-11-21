// A minimal reader for the one Mach-O this library loads: Apple's CoreADI, x86_64 slice, as it
// ships in iTunes 12.8.3 for Mac. Segments to map and the dyld bind stream to stub - nothing else.
//
// Classic LC_DYLD_INFO only. The builds this is for predate chained fixups, and a build that used
// them would be refused by the sha256 check in Java long before it got here.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace macadi {

struct Segment {
    uint64_t vmaddr;
    uint64_t vmsize;
    uint64_t fileoff;
    uint64_t filesize;
    bool writable;
};

struct Bind {
    uint64_t address;
    std::string symbol;
    int64_t addend;
};

class MachO {
public:
    // Throws macadi::Error on anything that is not a thin little-endian 64-bit Mach-O with a
    // bind stream this reader understands.
    static MachO parse(const std::vector<uint8_t> &image);

    const std::vector<Segment> &segments() const { return segments_; }
    const std::vector<Bind> &binds() const { return binds_; }

    // Distinct imported symbols, in first-seen order: binds, then lazy binds. The index of a symbol
    // in this list is what places its stub, so the order is part of the memory layout.
    std::vector<std::string> bindSymbols() const;

private:
    std::vector<Bind> readBinds(const std::vector<uint8_t> &d, size_t start, size_t len,
                                bool lazy) const;

    std::vector<Segment> segments_;
    std::vector<Bind> binds_;
};

}  // namespace macadi

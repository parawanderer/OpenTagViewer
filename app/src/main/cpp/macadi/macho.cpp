#include "macho.h"

#include <cstring>
#include <unordered_set>

#include "error.h"

namespace macadi {
namespace {

constexpr uint32_t MH_MAGIC_64 = 0xFEEDFACF;
constexpr uint32_t LC_SEGMENT_64 = 0x19;
constexpr uint32_t LC_DYLD_INFO = 0x22;
constexpr uint32_t LC_DYLD_INFO_ONLY = 0x80000022;

uint32_t u32At(const std::vector<uint8_t> &d, size_t o) {
    if (o + 4 > d.size()) throw Error("mach-o: truncated");
    uint32_t v;
    std::memcpy(&v, d.data() + o, 4);
    return v;  // little-endian host on every ABI this is built for
}

uint64_t u64At(const std::vector<uint8_t> &d, size_t o) {
    if (o + 8 > d.size()) throw Error("mach-o: truncated");
    uint64_t v;
    std::memcpy(&v, d.data() + o, 8);
    return v;
}

uint64_t uleb(const std::vector<uint8_t> &d, size_t &o) {
    uint64_t v = 0;
    unsigned shift = 0;
    for (;;) {
        if (o >= d.size()) throw Error("mach-o: truncated leb");
        const uint8_t b = d[o++];
        if (shift < 64) v |= static_cast<uint64_t>(b & 0x7F) << shift;
        shift += 7;
        if ((b & 0x80) == 0) return v;
    }
}

int64_t sleb(const std::vector<uint8_t> &d, size_t &o) {
    int64_t v = 0;
    unsigned shift = 0;
    for (;;) {
        if (o >= d.size()) throw Error("mach-o: truncated leb");
        const uint8_t b = d[o++];
        if (shift < 64) v |= static_cast<int64_t>(b & 0x7F) << shift;
        shift += 7;
        if ((b & 0x80) == 0) {
            if (shift < 64 && (b & 0x40) != 0) v |= -(static_cast<int64_t>(1) << shift);
            return v;
        }
    }
}

}  // namespace

MachO MachO::parse(const std::vector<uint8_t> &d) {
    if (u32At(d, 0) != MH_MAGIC_64) throw Error("mach-o: not a thin 64-bit Mach-O");
    const uint32_t ncmds = u32At(d, 16);

    MachO m;
    size_t bindOff = 0, bindLen = 0, lazyOff = 0, lazyLen = 0;
    size_t off = 32;
    for (uint32_t i = 0; i < ncmds; i++) {
        const uint32_t cmd = u32At(d, off);
        const uint32_t size = u32At(d, off + 4);
        if (cmd == LC_SEGMENT_64) {
            m.segments_.push_back(Segment{
                    u64At(d, off + 24), u64At(d, off + 32), u64At(d, off + 40),
                    u64At(d, off + 48), (u32At(d, off + 60) & 2) != 0});
        } else if (cmd == LC_DYLD_INFO || cmd == LC_DYLD_INFO_ONLY) {
            bindOff = u32At(d, off + 16);
            bindLen = u32At(d, off + 20);
            lazyOff = u32At(d, off + 32);
            lazyLen = u32At(d, off + 36);
        }
        if (size == 0) throw Error("mach-o: zero-sized load command");
        off += size;
    }

    std::vector<Bind> binds = m.readBinds(d, bindOff, bindLen, false);
    std::vector<Bind> lazy = m.readBinds(d, lazyOff, lazyLen, true);
    binds.insert(binds.end(), lazy.begin(), lazy.end());
    m.binds_ = std::move(binds);
    return m;
}

std::vector<Bind> MachO::readBinds(const std::vector<uint8_t> &d, size_t start, size_t len,
                                   bool lazy) const {
    std::vector<Bind> out;
    if (len == 0) return out;
    if (start + len > d.size()) throw Error("mach-o: bind stream outside the file");

    const size_t end = start + len;
    size_t o = start;
    std::string symbol;
    int64_t addend = 0;
    size_t seg = 0;
    uint64_t segOff = 0;

    auto push = [&]() {
        if (seg >= segments_.size()) throw Error("mach-o: bad bind segment");
        out.push_back(Bind{segments_[seg].vmaddr + segOff, symbol, addend});
    };

    while (o < end) {
        const uint8_t byte = d[o++];
        const uint8_t op = byte & 0xF0;
        const uint64_t imm = byte & 0x0F;
        switch (op) {
            case 0x00:  // DONE - but lazy streams carry one per entry, so keep going there
                if (!lazy) return out;
                break;
            case 0x10:  // SET_DYLIB_ORDINAL_IMM
            case 0x30:  // SET_DYLIB_SPECIAL_IMM
            case 0x50:  // SET_TYPE_IMM
                break;
            case 0x20:  // SET_DYLIB_ORDINAL_ULEB
                uleb(d, o);
                break;
            case 0x40: {  // SET_SYMBOL_TRAILING_FLAGS_IMM
                const void *nul = std::memchr(d.data() + o, 0, end - o);
                if (nul == nullptr) throw Error("mach-o: bad symbol");
                const size_t z = static_cast<const uint8_t *>(nul) - (d.data() + o);
                symbol.assign(reinterpret_cast<const char *>(d.data() + o), z);
                o += z + 1;
                break;
            }
            case 0x60:  // SET_ADDEND_SLEB
                addend = sleb(d, o);
                break;
            case 0x70:  // SET_SEGMENT_AND_OFFSET_ULEB
                seg = static_cast<size_t>(imm);
                segOff = uleb(d, o);
                break;
            case 0x80:  // ADD_ADDR_ULEB
                segOff += uleb(d, o);
                break;
            case 0x90:  // DO_BIND
                push();
                segOff += 8;
                break;
            case 0xA0:  // DO_BIND_ADD_ADDR_ULEB
                push();
                segOff += uleb(d, o) + 8;
                break;
            case 0xB0:  // DO_BIND_ADD_ADDR_IMM_SCALED
                push();
                segOff += imm * 8 + 8;
                break;
            case 0xC0: {  // DO_BIND_ULEB_TIMES_SKIPPING_ULEB
                const uint64_t count = uleb(d, o);
                const uint64_t skip = uleb(d, o);
                for (uint64_t i = 0; i < count; i++) {
                    push();
                    segOff += skip + 8;
                }
                break;
            }
            default:
                throw Error("mach-o: unsupported bind opcode " + std::to_string(op));
        }
    }
    return out;
}

std::vector<std::string> MachO::bindSymbols() const {
    std::vector<std::string> out;
    std::unordered_set<std::string> seen;
    for (const Bind &b : binds_) {
        if (seen.insert(b.symbol).second) out.push_back(b.symbol);
    }
    return out;
}

}  // namespace macadi

// One Anisette header set from the emulated Mac, on the host, printed as JSON.
//
//   macadi_cli <CoreADI x86_64 slice> <dispatch offset, hex> <store dir> <host uuid, 32 hex>
//              <frozen unix time, or -1 for the real clock> [--load-only]
//
// The store directory is read and never written: this is for comparing output, and a check that
// advanced the machine's state would make the next comparison against a different machine.
//
// --load-only stops after loading and one Initialize + IsMachineProvisioned, which needs no
// provisioned store and so can run anywhere the library can be downloaded.

#include <cstdio>
#include <cstdlib>
#include <dirent.h>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "core_adi.h"
#include "error.h"

namespace {

std::vector<uint8_t> readFile(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw macadi::Error("cannot read " + path);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

std::string hex(const std::vector<uint8_t> &b) {
    static const char *digits = "0123456789abcdef";
    std::string out;
    for (const uint8_t c : b) {
        out.push_back(digits[c >> 4]);
        out.push_back(digits[c & 15]);
    }
    return out;
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 6) {
        std::fprintf(stderr, "usage: %s coreadi dispatch_hex store_dir host_uuid_hex frozen [--load-only]\n",
                     argv[0]);
        return 2;
    }
    try {
        const std::vector<uint8_t> image = readFile(argv[1]);
        macadi::CoreAdi adi(image, std::strtoull(argv[2], nullptr, 16));

        const std::string uuidHex = argv[4];
        if (uuidHex.size() != 32) throw macadi::Error("host uuid must be 32 hex characters");
        uint8_t uuid[16];
        for (int i = 0; i < 16; i++) uuid[i] = static_cast<uint8_t>(std::stoul(uuidHex.substr(i * 2, 2), nullptr, 16));
        adi.setHostUuid(uuid);
        adi.setFrozenTime(std::stoll(argv[5]));

        if (argc > 6 && std::string(argv[6]) == "--load-only") {
            try {
                adi.headers(-2);
                std::printf("{\"loaded\": true, \"provisioned\": true}\n");
            } catch (const macadi::NotProvisioned &) {
                std::printf("{\"loaded\": true, \"provisioned\": false}\n");
            }
            return 0;
        }

        if (DIR *dir = opendir(argv[3])) {
            while (const dirent *e = readdir(dir)) {
                const std::string name = e->d_name;
                if (name == "." || name == "..") continue;
                adi.storePut(name, readFile(std::string(argv[3]) + "/" + name));
            }
            closedir(dir);
        } else {
            throw macadi::Error(std::string("cannot open store ") + argv[3]);
        }

        const macadi::Headers h = adi.headers(-2);
        std::printf("{\"mid\": \"%s\", \"otp\": \"%s\", \"routing\": %llu}\n", hex(h.mid).c_str(),
                    hex(h.otp).c_str(), static_cast<unsigned long long>(h.routing));
        return 0;
    } catch (const std::exception &e) {
        std::fprintf(stderr, "error: %s\n", e.what());
        return 1;
    }
}

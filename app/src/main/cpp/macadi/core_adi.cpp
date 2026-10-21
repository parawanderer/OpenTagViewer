#include "core_adi.h"

#include <algorithm>

#include "error.h"

namespace macadi {
namespace {

constexpr uint32_t INITIALIZE = 0x12DB31C5;
constexpr uint32_t IS_MACHINE_PROVISIONED = 0xB0EDA7AF;
constexpr uint32_t PROVISIONING_START = 0x716BD86C;
constexpr uint32_t PROVISIONING_END = 0x0B2B3196;
constexpr uint32_t SET_IDMS_ROUTING = 0x632B8D6E;
constexpr uint32_t GET_IDMS_ROUTING = 0x85FE63B0;
constexpr uint32_t SYNCHRONIZE = 0xA54951EC;
constexpr uint32_t REQUEST_OTP = 0xCFE0B46A;
constexpr uint32_t STORAGE_DISPOSE = 0x3E58E7F9;

void be32(std::vector<uint8_t> &m, uint32_t v) {
    for (int s = 24; s >= 0; s -= 8) m.push_back(static_cast<uint8_t>(v >> s));
}

void be64(std::vector<uint8_t> &m, uint64_t v) {
    for (int s = 56; s >= 0; s -= 8) m.push_back(static_cast<uint8_t>(v >> s));
}

std::vector<uint8_t> dsIdMessage(int64_t dsId) {
    std::vector<uint8_t> m;
    be64(m, static_cast<uint64_t>(dsId));
    return m;
}

const std::string LEGACY_STORE = "adi.pb";

}  // namespace

CoreAdi::CoreAdi(const std::vector<uint8_t> &image, uint64_t dispatch)
    : vm_(image), dispatch_(dispatch) {}

void CoreAdi::check(const char *op, Reply r) {
    if (r.rc < 0 || r.ret < 0) {
        throw Error(std::string("adi: ") + op + " failed: " +
                    std::to_string(r.ret < 0 ? r.ret : r.rc));
    }
}

CoreAdi::Reply CoreAdi::call(uint32_t code, const std::vector<uint8_t> &message) {
    std::vector<uint8_t> buf(std::max<size_t>(0x800, 8 + message.size()), 0);
    buf[3] = 1;  // encryption type: clear
    buf[7] = 1;
    std::copy(message.begin(), message.end(), buf.begin() + 8);
    const uint64_t bufAddr = vm_.scratch(buf.size());
    vm_.write(bufAddr, buf);

    const uint64_t params = vm_.scratch(0x20);
    vm_.write(params, std::vector<uint8_t>(0x20, 0));
    vm_.writeU64(params, bufAddr);
    vm_.writeU32(params + 0x08, static_cast<uint32_t>(8 + message.size()));
    vm_.writeU32(params + 0x0C, 4);
    vm_.writeU32(params + 0x10, 0);

    const int32_t rc = static_cast<int32_t>(static_cast<uint32_t>(vm_.invoke(dispatch_, {code, params})));
    const std::vector<uint8_t> head = vm_.read(bufAddr, 4);
    const int32_t ret = static_cast<int32_t>((static_cast<uint32_t>(head[0]) << 24) |
                                             (static_cast<uint32_t>(head[1]) << 16) |
                                             (static_cast<uint32_t>(head[2]) << 8) |
                                             static_cast<uint32_t>(head[3]));
    vm_.write(bufAddr + 4, std::vector<uint8_t>(4, 0));
    return Reply{rc, ret};
}

uint64_t CoreAdi::cell(uint64_t v) {
    const uint64_t a = vm_.scratch(8);
    vm_.writeU64(a, v);
    return a;
}

uint64_t CoreAdi::input(const std::vector<uint8_t> &data) {
    const uint64_t a = vm_.scratch(std::max<size_t>(data.size(), 16));
    vm_.write(a, data);
    return a;
}

std::vector<uint8_t> CoreAdi::slice(uint64_t ptr, uint64_t len) {
    const uint64_t p = vm_.readU64(ptr);
    const uint32_t n = vm_.readU32(len);
    if (p == 0 || n == 0) return {};
    return vm_.read(p, n);
}

void CoreAdi::beginSession() {
    // Provisioning writes the legacy whole-machine blob as well as the per-user one, and while it
    // is present the library reports the machine unprovisioned (see storePut). A process that
    // restarts never sees it, because storePut refuses it; one that provisions and then asks for
    // headers in the same process would, and sent Apple an empty machine id (ec -80009). So it
    // goes before every session, not only on load.
    vm_.files().erase(std::string(STORE_DIR) + "/" + LEGACY_STORE);
    vm_.resetSession();
    initialize();
}

CoreAdi::Reply CoreAdi::initialize() {
    vm_.scratchReset();
    return call(INITIALIZE, {});
}

CoreAdi::Reply CoreAdi::isMachineProvisioned(int64_t dsId) {
    vm_.scratchReset();
    return call(IS_MACHINE_PROVISIONED, dsIdMessage(dsId));
}

CoreAdi::Reply CoreAdi::storageDispose(uint64_t ptr) {
    std::vector<uint8_t> m;
    be64(m, ptr);
    return call(STORAGE_DISPOSE, m);
}

CoreAdi::Reply CoreAdi::setRouting(int64_t dsId, uint64_t routing) {
    vm_.scratchReset();
    std::vector<uint8_t> m = dsIdMessage(dsId);
    be64(m, routing);
    return call(SET_IDMS_ROUTING, m);
}

void CoreAdi::storePut(const std::string &name, const std::vector<uint8_t> &data) {
    // The legacy whole-machine blob is never restored: whenever it is present, the library
    // reports the machine unprovisioned without reading the per-user one (os-findmy-e,
    // mac_anisette.py). Restoring it would make every later session look unprovisioned.
    if (name == LEGACY_STORE) return;
    vm_.files()[std::string(STORE_DIR) + "/" + name] = data;
}

std::vector<std::pair<std::string, std::vector<uint8_t>>> CoreAdi::storeFiles() {
    const std::string prefix = std::string(STORE_DIR) + "/";
    std::vector<std::pair<std::string, std::vector<uint8_t>>> out;
    for (const auto &f : vm_.files()) {
        if (f.first.rfind(prefix, 0) == 0) out.emplace_back(f.first.substr(prefix.size()), f.second);
    }
    return out;
}

Headers CoreAdi::headers(int64_t dsId) {
    beginSession();
    if (isMachineProvisioned(dsId).ret != 0) throw NotProvisioned();

    vm_.scratchReset();
    const uint64_t midP = cell(0), midL = cell(0), otpP = cell(0), otpL = cell(0);
    std::vector<uint8_t> m = dsIdMessage(dsId);
    for (const uint64_t v : {midP, midL, otpP, otpL}) be64(m, v);
    const Reply otpReply = call(REQUEST_OTP, m);
    Headers h{slice(midP, midL), slice(otpP, otpL), 0};
    // The library hands back pointers into its own storage; release them.
    if (!h.mid.empty() && !h.otp.empty()) storageDispose(vm_.readU64(midP));
    check("RequestOTP", otpReply);

    vm_.scratchReset();
    const uint64_t routingCell = cell(0);
    std::vector<uint8_t> r = dsIdMessage(dsId);
    be64(r, routingCell);
    check("GetIDMSRouting", call(GET_IDMS_ROUTING, r));
    h.routing = vm_.readU64(routingCell);
    return h;
}

std::pair<std::vector<uint8_t>, uint32_t> CoreAdi::provisioningBegin(
        int64_t dsId, const std::vector<uint8_t> &spim) {
    beginSession();
    if (isMachineProvisioned(dsId).ret == 0) throw AlreadyProvisioned();

    vm_.scratchReset();
    const uint64_t spimBuf = input(spim);
    const uint64_t cpimP = cell(0), cpimL = cell(0), session = cell(0);
    std::vector<uint8_t> m = dsIdMessage(dsId);
    be64(m, spimBuf);
    be32(m, static_cast<uint32_t>(spim.size()));
    for (const uint64_t v : {cpimP, cpimL, session}) be64(m, v);
    check("ProvisioningStart", call(PROVISIONING_START, m));
    return {slice(cpimP, cpimL), vm_.readU32(session)};
}

void CoreAdi::provisioningFinish(int64_t dsId, uint32_t session, const std::vector<uint8_t> &ptm,
                                 const std::vector<uint8_t> &tk, uint64_t routing) {
    vm_.scratchReset();
    const uint64_t ptmBuf = input(ptm), tkBuf = input(tk);
    std::vector<uint8_t> m;
    be32(m, session);
    be64(m, ptmBuf);
    be32(m, static_cast<uint32_t>(ptm.size()));
    be64(m, tkBuf);
    be32(m, static_cast<uint32_t>(tk.size()));
    check("ProvisioningEnd", call(PROVISIONING_END, m));
    check("SetIDMSRouting", setRouting(dsId, routing));
}

std::pair<std::vector<uint8_t>, std::vector<uint8_t>> CoreAdi::sync(
        int64_t dsId, const std::vector<uint8_t> &sim) {
    beginSession();
    vm_.scratchReset();
    const uint64_t simBuf = input(sim);
    const uint64_t midP = cell(0), midL = cell(0), srmP = cell(0), srmL = cell(0);
    std::vector<uint8_t> m = dsIdMessage(dsId);
    be64(m, simBuf);
    be32(m, static_cast<uint32_t>(sim.size()));
    for (const uint64_t v : {midP, midL, srmP, srmL}) be64(m, v);
    check("Synchronize", call(SYNCHRONIZE, m));
    return {slice(midP, midL), slice(srmP, srmL)};
}

}  // namespace macadi

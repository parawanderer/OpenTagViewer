# The emulated Mac's CPU emulator: Unicorn, and how to swap it

Decision record, 2026-10-23. Context: [#248](https://github.com/parawanderer/OpenTagViewer/issues/248)
and [#274](https://github.com/parawanderer/OpenTagViewer/pull/274), which signs in with Apple's
macOS CoreADI running under an x86_64 emulator on the phone.

## Decision

**Ship Unicorn**, and replace it later only if somebody has a concrete reason to. Unicorn is
GPL-2.0, so an APK containing it is distributed under GPL-2.0 terms. That carries two
obligations that are not optional and are owed from the first release, not from the first
complaint:

- a licence notice for Unicorn in the app or the release;
- `libmacadi.so` loaded **in its own process**, not the app's. That is needed for crash
  isolation regardless (AGENTS.md rule 4, #232), and it also keeps the GPL code at arm's length
  from the Apache-2.0 libraries and the proprietary map SDKs. Loaded in-process, the FSF's view
  is that the whole APK is one combined work, and GPL-2.0 is incompatible with both of those.

## Why replacing it later is cheap

The emulated Mac's identity lives in its store and host UUID, not in the emulator. An emulator
that produces **the same bytes** for the same inputs is invisible to Apple and to users: no new
device-list entry, no sign-in. So the swap is reversible, on one condition, which
`scripts/adi-emulator-diff/` checks.

## What was surveyed

Permissively licensed x86-64 emulators, as of 2026-10:

| Project | Licence | Verdict |
| --- | --- | --- |
| [icicle-emu](https://github.com/icicle-emu/icicle-emu) | MIT OR Apache-2.0 | Tried; see below. Library-first, Unicorn-like (map, hooks, registers), x86-64 through Ghidra's SLEIGH specification |
| [mwemu](https://github.com/mwemuorg/mwemu) | Apache-2.0 | Second candidate. Pure-Rust interpreter with a Mach-O loader; 339 instructions implemented, so obfuscated SSE code is the risk |
| [remu64](https://github.com/trumank/remu64) | MIT | Weekend project by its own description. Too thin |
| [Triton](https://github.com/JonathanSalwan/Triton) | Apache-2.0 | Can execute concretely, but far slower per instruction |
| [FEX](https://github.com/FEX-Emu/FEX), [box64](https://github.com/ptitSeb/box64) | MIT | Fast and mature, but they run whole Linux processes; not embeddable to call one function in a Mach-O with our own stubs |

## The icicle experiment

`scripts/adi-emulator-diff/run.sh` puts icicle under [sacca97/os-findmy-e](https://github.com/sacca97/os-findmy-e)'s
Rust `adi-core` as a second `vm.rs`. The macOS userland is os-findmy-e's own, generated
verbatim from its `vm.rs`; only the CPU underneath changes. Unicorn's code hook on the import
stub page becomes one breakpoint per handled stub. Both builds then make the same calls with a
frozen clock and a fixed host UUID, and the outputs are diffed.

Result on an Apple M-series Mac, iTunes 12.8.3 CoreADI 4.19: `Initialize`; `IsMachineProvisioned`,
`RequestOTP` and `GetIDMSRouting` on an unprovisioned machine; and `ProvisioningStart` with five
synthetic spims of 0 to 4096 bytes are **byte-identical** under both - return codes, outputs and
the hash of every file the library wrote.

| | Unicorn | icicle |
| --- | --- | --- |
| Load | 4 ms | ~70 ms (compiles the SLEIGH spec) |
| The calls above, 5.5M guest instructions | 13 ms | ~410 ms interpreted, ~515 ms with its JIT |

### What that does not establish

- **The OTP path was never reached.** Every call above can be answered offline, and a synthetic
  spim is rejected early. The heavily obfuscated code that computes a real OTP only runs on a
  provisioned machine, which needs one anonymous provisioning exchange with Apple. That is the
  comparison that settles it, and it has not been run. The driver already has the shape for it:
  load a provisioned store, freeze the clock, compare `RequestOTP`.
- **Speed on a phone is unmeasured.** Roughly 30x slower than Unicorn on a Mac, for a sum of
  about fifteen calls rather than one header set. Fine for a sign-in, possibly not for every
  background fetch.
- icicle needs Ghidra's x86 processor files at runtime (Apache-2.0, a few MB of text), so an APK
  would have to carry them or a precompiled form.
- **#274's C++ port is a third implementation.** This compares os-findmy-e's Rust under two
  emulators; it says nothing about whether `app/src/main/cpp/macadi/` matches either. The same
  driver pointed at `libmacadi.so` would.

## Running it

```bash
python scripts/locate_mac_coreadi.py --remote --out /tmp/CoreADI.x86_64   # from #274
scripts/adi-emulator-diff/run.sh /tmp/CoreADI.x86_64
```

Needs git, cargo and cmake. It clones os-findmy-e, icicle and a sparse Ghidra at pinned commits
into a work directory, and sends nothing to Apple. It exits non-zero if either icicle run
differs from Unicorn.

One trap, for whoever extends the shim: **icicle's memory and register reads take `&mut self`**
(they fill translation caches). Casting `&` to `*mut` to call them from a `&self` reader is
undefined behaviour, and in release builds it segfaulted before printing anything, which looks
like a broken spec or a stack overflow. The shim holds the VM in an `UnsafeCell` instead.

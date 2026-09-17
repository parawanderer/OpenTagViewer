# Apple's ADI libraries on bionic, without an emulator

Loads and initialises Apple's real `libstoreservicescore.so` on plain Linux, under Android's own
dynamic linker and libc. `.github/workflows/adi-on-bionic.yml` runs it on x86_64 and arm64, against
Android 9 and Android 14 bionic.

## Why this exists

Issue #232 was a `SIGBUS` inside `libstoreservicescore.so`'s static constructors during `dlopen`, on
some arm64 phones. The cause was ours: the stub for `mediaplatform::WorkQueue::makeWorkQueue` never
wrote the `std::shared_ptr` it returns by value, and Apple's constructor incremented a reference
count through whatever was left on the stack. Nothing in CI loaded Apple's library, so nothing saw it.

A custom ELF loader, the way Dadoum/Provision runs ADI on Linux, would not have caught it either:
Provision never runs `.init_array`. This uses the real Android linker, so every constructor runs.

## How it works

Bionic only needs a Linux kernel. So:

1. `CMakeLists.txt` builds our stubs (through the same `stubs/AppleStubs.cmake` as the APK) and
   `adi_probe.c` with the NDK. The ADI entry point names are read from `AdiFunction.java`.
2. `fetch_apple_libs.py` pulls Apple's three libraries out of the Apple Music APK with range requests
   and checks them against `app/src/main/assets/adi-libraries.json`.
3. `extract_bionic.sh` (Android 9, plain files, `debugfs`) or `extract_bionic_apex.sh` (Android 10+,
   the `com.android.runtime` APEX inside a dynamic partition, needs `sudo` to mount; uses
   `lp_extract.py` for the partition) takes `linker64` and the libraries the probe needs out of an
   AOSP emulator system image.
4. They are copied to `/system/bin/linker64` and `/system/lib64/`, and the probe is run directly: the
   kernel honours its `PT_INTERP`.
5. `run_probe.sh` runs it twice. With the stubs that ship it must load, resolve every ADI entry point,
   initialise, and get "not provisioned" back from `ADIGetLoginCode`. With `makeWorkQueue` as it was
   before the fix, it must fail - which is what shows the job can catch the bug at all.

No provisioning and no Apple account. Nothing is sent to Apple beyond downloading the libraries.

## What the pre-fix stub does here

Measured on GitHub's runners when this was set up:

| | Android 9 bionic | Android 14 bionic |
| --- | --- | --- |
| arm64 | `SIGBUS` `BUS_ADRALN` at `libstoreservicescore.so+0x23312c` - the address in #232's tombstone | same |
| x86_64 | `SIGSEGV` inside `libstoreservicescore.so` | no crash: the leftover slot was zero. Fails only because a generated stub was called |

So whether it crashes depends on the architecture and on the bionic build, as it did on phones - and
the probe's check that no generated stub was called is load-bearing, not decoration.

## Not covered

- The Java and JNI layer (`AdiLibrary`, `LocalAnisette`, the separate probe process). The emulator
  suite covers those.
- Provisioning and one-time passwords, which need Apple's servers.

## Running it

It needs Linux on the target architecture, the NDK, and `sudo` for the Android 14 extraction. The
workflow is the reference; its steps run as-is on an Ubuntu machine with `ANDROID_NDK_HOME` set.

# PoC: native Anisette on Android — what is verified

> **Status: historical record, kept for its measurements.** This is the proof-of-concept log
> that preceded the app's native Anisette implementation, and it is retained because the numbers
> in it — download sizes, load timings, the stub surface, the eleven entry points — cost real
> device time to obtain and would be expensive to rediscover.
>
> It is a lab notebook, not a description of the shipped code. Two kinds of reference in it have
> since moved on:
>
> - **The measurement scripts were throwaway** and are not in the tree. Each is described well
>   enough to rewrite, and `python -m zipfile` plus an ELF parser is the whole toolkit.
> - **The probe and its tests were implemented properly** and renamed. See
>   `app/src/main/cpp/adi.cpp`, `app/src/main/java/.../anisette/`, and the tests in
>   `app/src/androidTest/.../anisette/`.
>
> For the authentication flow this Anisette work feeds, see
> [findmy-export/01-authentication.md](./findmy-export/01-authentication.md).

2026-08-10. Everything below was run from a Windows desktop with no Android device involved,
so it verifies the parts that do not need one. It stops exactly where a device becomes
necessary.

Reference implementation: [Dadoum/Provision](https://github.com/Dadoum/Provision) —
`lib/provision/adi.d` and `docs/ADI.md`, plus
[anisette-v3-server](https://github.com/Dadoum/anisette-v3-server) `source/app.d` for the
server wrapper around it.

---

## 1. The libraries are still published, and reachable

```
HTTP/1.1 200 OK
Content-Length: 142139820
Content-Type: application/vnd.android.package-archive
```

`https://apps.mzstatic.com/content/android-apple-music-apk/applemusic.apk` — Apple's own CDN,
no account, no auth. Nothing is redistributed by us, which is the same position every public
Anisette server is in.

## 2. We do not need to download 142 MB

`anisette-v3-server` downloads the whole APK, unzips it, and keeps two files. That is fine for
a server doing it once and unacceptable on a phone.

A zip's central directory is at the end, so HTTP range requests can read the directory, locate
the two members, and fetch only those. Measured:

```
APK is 142 MB
central directory: 272 kB at offset 141867668
  lib/arm64-v8a/libCoreADI.so:            1.79 MB (deflated), ELF=True
  lib/arm64-v8a/libstoreservicescore.so:  2.39 MB (deflated), ELF=True

Downloaded 2.6 MB of 142 MB (1.8%)
```

**1.8% of the bytes.** On a phone that is the difference between a feature people accept and
one they cancel. Script: `scratchpad/poc_fetch_adi.py`.

The APK also carries `armeabi-v7a`, `x86` and `x86_64`, so the ABI is a runtime choice — on a
device, simply the device's own.

## 2a. But two libraries is not the real number — `DT_NEEDED` is

2026-08-10. `libstoreservicescore.so` does not stand alone:

```
libstoreservicescore.so  NEEDED libmediaplatform.so, libCoreFoundation.so, libc++_shared.so, ...
libCoreADI.so            NEEDED liblog, libdl, libc          <- clean
```

`dlopen()` follows `DT_NEEDED`. Dadoum's hand-written loader does not, which is why
anisette-v3-server gets away with keeping two files. The full transitive closure, measured
(`scratchpad/poc_closure.py`):

```
libstoreservicescore.so   2.39 MB   ->  libmediaplatform, libCoreFoundation, libc++_shared
libmediaplatform.so       2.36 MB   ->  libcurl, libCoreFoundation, libicu{i18n,uc,data}_sv_apple,
                                        libdispatch, libxml2, libc++_shared
libCoreFoundation.so      2.01 MB   ->  libicu*, libcurl, libxml2, libBlocksRuntime, libdispatch
libicudata_sv_apple.so   10.23 MB
libcurl.so                3.40 MB       libicui18n_sv_apple.so  2.60 MB
libicuuc_sv_apple.so      1.75 MB       libxml2.so              2.00 MB
libc++_shared.so          1.03 MB       libdispatch.so          0.38 MB
libBlocksRuntime.so       0.01 MB

Closure: 11 libraries, 28.2 MB unpacked, 11.3 MB downloaded (7.9% of the APK)
```

Apple shipped their own CoreFoundation, libdispatch and ICU for Android, and ADI sits on top
of them. All four ABIs carry the complete set, so no ABI is a special case.

This splits the feature into two shapes:

| Path | Cost | Needs |
| --- | --- | --- |
| **A — honour `DT_NEEDED`** | 11.3 MB download, 28 MB on disk, 11 libraries | nothing; plain `dlopen` in dependency order |
| **B — ignore them** (Provision's approach) | 2.6 MB, 2 libraries | the manual ELF mapper |

B is the shipping form. A is the one worth building first, because it needs no mapper and so
can answer *"do the eleven ADI calls work on Android at all"* immediately. If A works, the
mapper stops being a blocker and becomes a size optimisation — a far better project shape than
writing an ELF loader and only then finding out.

Ordering is what makes A work without any linker trickery: bionic resolves `DT_NEEDED` against
already-loaded libraries by SONAME, so opening the closure bottom-up satisfies each library's
dependencies before it is opened. Nothing is on the default search path and nothing needs to be.

## 3. `libCoreADI.so` is built to be loaded by an Android JVM

It exports exactly three symbols:

```
JNI_OnLoad
cvu8io98wun
vdfut768ig
```

`JNI_OnLoad` is the entry point Android's `System.loadLibrary` calls. This library expects to
live in a JVM process. That is the whole premise of doing this in-app rather than over the
network, confirmed at the binary level.

The two scrambled names are CoreADI's real entry points, and per `docs/ADI.md` they are
deliberately hard to reverse. **We do not call them.** We call the wrappers exported by
`libstoreservicescore.so`, which call into them.

## 4. All eleven ADI entry points exist in today's build

From `lib/provision/adi.d`, with signatures. Every one verified present in the
`libstoreservicescore.so` pulled today:

| Function | Symbol | Signature |
| --- | --- | --- |
| `ADILoadLibraryWithPath` | `kq56gsgHG6` | `int(const char*)` |
| `ADISetAndroidID` | `Sph98paBcz` | `int(const char*, uint)` |
| `ADISetProvisioningPath` | `nf92ngaK92` | `int(const char*)` |
| `ADIProvisioningErase` | `p435tmhbla` | `int(ulong)` |
| `ADISynchronize` | `tn46gtiuhw` | `int(ulong, ubyte*, uint, ubyte**, uint*, ubyte**, uint*)` |
| `ADIProvisioningDestroy` | `fy34trz2st` | `int(uint)` |
| `ADIProvisioningEnd` | `uv5t6nhkui` | `int(uint, ubyte*, uint, ubyte*, uint)` |
| `ADIProvisioningStart` | `rsegvyrt87` | `int(ulong, ubyte*, uint, ubyte**, uint*, uint*)` |
| `ADIGetLoginCode` | `aslgmuibau` | `int(ulong)` |
| `ADIDispose` | `jk24uiwqrg` | `int(void*)` |
| `ADIOTPRequest` | `qi864985u0` | `int(ulong, ubyte**, uint*, ubyte**, uint*)` |

```
  FOUND    kq56gsgHG6      FOUND    fy34trz2st
  FOUND    Sph98paBcz      FOUND    uv5t6nhkui
  FOUND    nf92ngaK92      FOUND    rsegvyrt87
  FOUND    p435tmhbla      FOUND    aslgmuibau
  FOUND    tn46gtiuhw      FOUND    jk24uiwqrg
                           FOUND    qi864985u0
```

Symbol dumper: `scratchpad/poc_elf_symbols.py` (pure Python, no NDK).

**These names are not stable across builds** — `docs/ADI.md` says obfuscation differs per
build. So the loader has to fail legibly when a symbol is missing, and the version of the APK
it pulled should be recorded.

---

## 5. RESOLVED (2026-08-10): it loads, and ADI initialises

Run on the Gradle managed device (Pixel 6, API 34, x86_64, aosp-atd) by a load test against a
native probe. All three steps passed, `skipped="0"`. (Both have since been implemented as
`app/src/main/cpp/adi.cpp` and the tests in `app/src/androidTest/.../anisette/`.)

**W^X does not block it.** SELinux said so itself:

```
avc: granted { execute } for path=".../files/adi-poc/x86_64/libc++_shared.so"
     scontext=u:r:untrusted_app:s0 tcontext=u:object_r:app_data_file:s0 tclass=file
```

All eleven libraries `dlopen`'d out of `filesDir` in dependency order, RTLD_NOW | RTLD_GLOBAL,
no linker tricks and nothing on the search path. All eleven ADI entry points resolved. Then:

```
ADILoadLibraryWithPath(/data/user/0/.../files/adi-poc/x86_64) = 0
```

0 is ADI's success code. Note what that implies: `libCoreADI.so` was never opened by us -
`libstoreservicescore.so` found and loaded it itself. Apple's CoreFoundation, libdispatch, ICU
and CoreADI all initialise inside an ordinary Android app process.

**Consequence: the manual mmap+relocate ELF loader is not needed.** That was the only
open item that could have made this a multi-week project, and it is gone. Path B (ignoring
DT_NEEDED to save ~25 MB) is now purely a size optimisation and can wait indefinitely.

### Confirmed on real hardware the same day

Re-run on a Galaxy S25 (SM-S938B), **arm64-v8a, Android 16**, from Studio with
`-e adiPoc true` as an instrumentation extra param. All three steps passed, and there is no
`avc: denied` anywhere in logcat:

```
downloaded 12.6 MB of 142 MB (8.9%)
dlopen(.../arm64-v8a/libstoreservicescore.so) ok
resolved kq56gsgHG6 ... qi864985u0        (all eleven)
ADILoadLibraryWithPath(.../arm64-v8a) = 0
```

So the emulator result was not a policy artefact of aosp-atd. Two numbers worth keeping:

- **The download took 2.1 seconds.** This is a brief one-time spinner, not a progress-bar
  problem. Design for the slow case rather than around it.
- **All eleven libraries loaded in 11 ms.** Dependency-ordered loading costs nothing.

Correction to section 2a: the real cost is **12.6 MB, not 11.3 MB**. That script counted only
the DT_NEEDED graph, and `libCoreADI.so` (1.79 MB) is not a declared dependency of anything -
libstoreservicescore opens it by path at runtime. 12.6 MB is the measured figure.

## 7. RESOLVED (2026-08-10): provisioning works end to end

`AdiProvisioningTest` on the managed device, against Apple's live servers.
`tests="1" failures="0" skipped="0"`:

```
provisioning as 3ED7500C-29FA-4A26-99A2-47D8BD863E01 (ADI id bb1037520408cb27)
ADI initialised, persisting to .../files/adi-provisioning-state
provisioned: session 1458087437 completed
already provisioned          <- second call correctly a no-op
```

Apple accepted an entirely invented machine identity, ADI swallowed the persistent token and
trust key, and ADIGetLoginCode then returned 0. **There is no research left in this feature.**

Three things it took to get there, all of them things the reference does differently:

**1. The identity lengths are checked.** See section on invented identity - `rndGen.take(2)` is
two uints, not two bytes. Wrong length gives -45001.

**2. `gsa.apple.com` fails TLS against Android's trust store.** It chains to the original
"Apple Root CA", which Android does not ship:

```
SSLHandshakeException: Unacceptable certificate: CN=Apple Root CA, O=Apple Inc., C=US
```

Provision solves this with `request.sslSetVerifyPeer(false)`. **We do not** - turning off
certificate verification in an authentication flow inside a phone app makes it interceptable by
anything on the network path. Instead the one missing root is added for that one domain in
`network_security_config.xml`, alongside `system`, with the certificate stored as PEM text
(SHA-256 `b0b1730e...f024`, published at https://www.apple.com/appleca/). That is *stricter*
than the default, not looser.

**3. The stub question is answered on the path that mattered.** `makeWorkQueue` was called,
returned NULL, and provisioning completed anyway. So stubbing holds through provisioning, and
that symbol is now recorded in `libmediaplatform.expected` - logged at INFO rather than raising
an alarm, because an alarm that fires on every successful run is one nobody reads.

### What this does not yet prove

- **ADIOTPRequest**, the per-login one-time password. Provisioning is the hard half; this is
  the half that runs on every login.
- **The Python side** - a provider that hands FindMy.py the headers, alongside the existing
  remote one.
- Historical note, now resolved: `ADILoadLibraryWithPath` returning 0 meant ADI initialised. The
  ProvisioningStart -> Apple -> ProvisioningEnd round trip is untested.
- Apple can change the APK layout or re-obfuscate the symbols at any time, so the failure
  paths matter as much as the happy one.

## 6. The 28 MB can probably be stubbed away, without an ELF loader

Measured by `scratchpad/poc_stub_surface.py`. `libstoreservicescore.so` has 405 undefined
symbols, and they come from only three libraries:

```
libc++_shared.so       141 symbols   [1.0 MB]
libmediaplatform.so    125 symbols   [2.4 MB]
libCoreFoundation.so    93 symbols   [2.0 MB]
(platform / bionic)     46 symbols   - already present
```

**ICU, curl, libxml2, libdispatch and BlocksRuntime are never referenced.** They are in the
closure only because CoreFoundation and mediaplatform depend on them. ICU alone is 14.6 MB.

bionic satisfies DT_NEEDED by SONAME against already-loaded libraries and does not check who
built them. So two generated stub libraries - SONAME `libCoreFoundation.so` and
`libmediaplatform.so`, exporting those 218 names - satisfy the linker at a few kB each, and the
20 MB tail behind them stops being reachable because nothing declares a dependency on it.

```
today (path A):   11 libraries   11.3 MB downloaded   28.2 MB on disk
with stubs:        3 libraries    2.9 MB downloaded    5.2 MB on disk
```

Keep Apple's real `libc++_shared.so`: the mangled names are `__ndk1`-namespaced, so NDK 27's
own libc++ should not be assumed ABI-identical without checking.

### CONFIRMED (2026-08-10): it works, and one function is called

`StubbedAdiLoadTest` on the managed device. All three steps passed, `skipped="0"`. Three
libraries downloaded instead of twelve, all eleven ADI entry points still resolved, and
`ADILoadLibraryWithPath` still returned 0.

Of **218 stubbed symbols, exactly one was reached**:

```
adi-stub: mediaplatform::WorkQueue::makeWorkQueue(std::string const&, WorkQueueType)
```

None of the 93 CoreFoundation symbols were touched at all.

**And checking Provision shows this is lower risk than it looks.** Two things:

*When it happens.* By timestamp, `makeWorkQueue` fired at `48.008` - the same millisecond as
`dlopen(libstoreservicescore.so) ok`, and before `ADILoadLibraryWithPath` at `48.026`. So it is
a **static constructor running during dlopen**, not something ADI called.

*Why Provision never sees it.* Their symbol table (`lib/provision/symbols.d`) is 29 entries and
every one is libc/bionic - `dlopen`, `malloc`, `pthread_*` as `emptyStub`, `read`, `write`,
`__errno`, `__system_property_get`, `arc4random`. No CoreFoundation, no mediaplatform. Anything
else becomes a trampoline that *throws* `UndefinedSymbolException`. And their loader **never
runs `.init_array`** - `androidlibrary.d` has no constructor handling at all.

So in the reference implementation that constructor never runs, and whatever global holds the
work queue stays zero: functionally identical to our stub returning NULL. Every public Anisette
server is a live demonstration that ADI's provisioning paths work with no work queue. Worth
watching during provisioning, but not an open question.

This is why the stubs log and count instead of aborting. A stub that returns 0 produces a
*silent wrong answer*, which is worse than a crash, so each one logs an actionable error and
increments a counter that `<Library>_adi_stub_calls()` exposes - the hook the runtime fallback
to a remote Anisette server should hang off.

## What is left, and where a device becomes necessary

Everything above is acquisition and symbol resolution. The remaining work is the actual
integration:

1. **JNI wrapper** over the eleven functions. `dlopen` `libstoreservicescore.so`, then
   `ADILoadLibraryWithPath` pointing at the directory holding `libCoreADI.so`.
2. **Provisioning**: `ADISetAndroidID` with a generated identifier, `ADISetProvisioningPath`
   into app storage, then `ADIProvisioningStart` → POST to Apple's `midStartProvisioning` →
   `ADIProvisioningEnd`. One time, persisted. `adi.d` around lines 380–500 has the exact
   request shape.
3. **Headers per login**: `ADIOTPRequest` produces the one-time password and machine ID that
   the login flow needs.
4. **Python side**: a provider that calls back into Java, alongside `RemoteAnisetteProvider`.
5. **Fallback**: keep the remote provider. A local path that fails must degrade to today's
   behaviour, not to a broken login.

None of steps 1–3 can be tested without a device — the libraries are arm64 Android binaries,
and this desktop cannot load them.

### The device identity is invented, not derived

Worth settling, because it sounds like it should be the hard part and is not. The whole
identity a public Anisette server presents to Apple is randomly generated on first run:

```d
v1Device.serverFriendlyDescription  = clientInfo;                    // a UA-ish string
v1Device.uniqueDeviceIdentifier     = randomUUID().toString().toUpper();
v1Device.adiIdentifier              = (cast(ubyte[]) rndGen.take(2).array()).toHexString().toLower();
v1Device.localUserUUID              = (cast(ubyte[]) rndGen.take(8).array()).toHexString().toUpper();
```

**Careful with those lengths - I got them wrong first time round and ADI rejected it with
-45001 (invalid parameters).** `rndGen.take(2)` takes two *uints*, not two bytes: that is
8 bytes, 16 hex characters. `take(8)` is 32 bytes, 64 hex characters - which is what
`X-Apple-I-MD-LU` looks like in real Apple traffic. The values are invented, but the sizes are
checked.

It is then persisted and reused, so the machine stays the same machine across logins.

This matches what rustpush's author said independently in
[rustpush#18](https://github.com/OpenBubbles/rustpush/issues/18):

> You still need apple hw IDs to send to Apple's server (X-MME-Client-Info or whatever) but you
> should be able to make something up, i don't think that stuff is strictly validated

So there is no hardware attestation to defeat and nothing to extract from a real device. Every
public Anisette server in use today runs on invented identity, which is the working proof.

What matters is that the identity is **generated once and kept** — regenerating per login would
make every session look like a new machine, which is exactly the pattern 2FA is designed to
notice.

## Honest assessment

The scope has not changed: this is still weeks, and the hard part was never acquisition. What
this PoC removes is uncertainty. Every question that could be answered without a device now is
answered, and none of the answers were bad:

- the libraries are still published, and fetching them costs 2.6 MB
- they are Android-native, JNI-ready, and require no emulation
- all eleven entry points exist, with known signatures, in the current build

There is no research left in the *sequence*. The identity is invented, the entry points are
known, the libraries are fetchable cheaply, and every public Anisette server is a live
demonstration that the calls work.

### But there is no precedent for doing it on Android, and that matters

Checked, expecting to find one:

- **Provision / anisette-v3-server** (D) — `androidlibrary.d`, a hand-written ELF loader, for
  running Android libraries on desktop Linux.
- **omnisette** (Rust, OpenBubbles) — depends on Dadoum's `android-loader`, and uses it in
  `store_services_core/posix_macos.rs` and `posix_windows.rs`. **There is no Android file.**
  Its only Anisette features are `remote-anisette-v3` and `remote-clearadi`; `clearadi` itself
  is an empty submodule pointing at a closed-source stub.

So **OpenBubbles on Android uses a remote Anisette server**, exactly as this app does today. The
whole ecosystem writes Android-library loaders *for desktop*, and on the one platform where the
libraries are native, everybody calls a server.

That is not evidence it cannot work — these projects are desktop-first, and OpenBubbles' Android
client is a Flutter app that already had a server to talk to. But it does mean there is nothing
to copy for the loading step, and the one Android-specific obstacle is unanswered:

**W^X.** Apps targeting API 29+ cannot execute code from writable app-home storage, and
`targetSdk` here is 35. `dlopen()` on a `.so` downloaded into the app's data directory is the
thing the platform is designed to refuse. If that holds, the options are:

| Option | Consequence |
| --- | --- |
| `System.load()` from app data dir | free if it works; needs a fallback if it does not |
| **Map it ourselves** | always works, no file is ever executed — see below |
| Bundle in `jniLibs` | always works, but **we would be redistributing Apple's binaries** — the exact position fetching-from-Apple's-CDN avoids |

### `dlopen` is not the only way to load a library

W^X restricts executing **files** from app-writable storage. It does not stop an app mapping
anonymous memory and marking it executable — ART's own JIT depends on precisely that, which is
why `execmem` is granted to the `untrusted_app` SELinux domain.

And a loader that does this already exists, in the reference implementation. `androidlibrary.d`:

```d
allocation = MmapAllocator.instance.allocate(allocSize + shift);   // anonymous, not file-backed
mprotect(..., PROT_READ | PROT_WRITE);                             // writable while loading
allocation[headerStart..headerEnd] = elfFile[fileStart..fileEnd];  // copy segments in
mprotect(..., prot);                                               // then read+execute
// ... relocations applied by hand
```

No file is ever executed. The bytes are copied into anonymous pages and relocated there.

**On Android this loader gets simpler, not harder.** Its companion `symbols.d` exists to map
bionic symbols (`open`, `pthread_create`, `__errno`, ...) onto glibc equivalents, because it
runs on desktop. On Android those symbols are the real ones — resolution is `dlsym` against the
platform's own libc, and that entire table becomes unnecessary.

Implementation would be an NDK component: port the segment mapping and relocation logic to
C/C++ (Android's own linker is the reference), or reuse Dadoum's `android-loader` as a Rust
cdylib. Either is real work, but it is bounded, well-understood work with a working
implementation to read.

### The experiment, in order

1. **Try `System.load()` from `filesDir` first.** If it works, none of the above is needed.
   Ten minutes on a device.
2. **If W^X refuses it**, map it manually. Known to work, code to copy, and simpler here than
   in the project it comes from.
3. Bundling stays the last resort, because it is the only option that changes the licensing
   position.

Either way the feature is reachable without shipping Apple's binaries — which was the real
question.

What remains is a JNI layer, a provisioning flow persisted to app storage, a Python provider
that calls back into Java, and a fallback to `RemoteAnisetteProvider` when any of it fails.
That is ordinary work — a fair amount of it, on a device, but ordinary.

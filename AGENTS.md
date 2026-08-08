# AGENTS.md

Rules for agents working on OpenTagViewer. Written for automated contributors, but the
constraints apply to anyone.

## What this project is

An Android app that shows Apple AirTag locations without an Apple device. Tags are exported
from a Mac once (see the wiki), imported as a zip, and located via Apple's Find My network
through [FindMy.py](https://github.com/malmeloo/FindMy.py).

| Layer | Technology |
| --- | --- |
| App | Android, **Java** (not Kotlin), Gradle Kotlin DSL, `compileSdk`/`targetSdk` 35, `minSdk` 24 |
| Apple protocol | **Chaquopy** — CPython 3.12 embedded, bridged in `app/src/main/python/main.py` |
| Persistence | **Room** (`opentagviewer-db`) |
| Async | **RxJava3** through repositories and services |
| Boilerplate | **Lombok** (`@Builder`, `@Getter` on entities) |
| Desktop exporter | `python/` — tkinter wizard, PyInstaller |

`app/src/main/python/` is packaged into the APK by Chaquopy. Nothing in it may import
Android or Java types — that is what makes it testable on plain CPython.

---

## Rules

### 1. Never ship a Room schema change without a migration

Bumping `version` without a `Migration` throws at first database access **on upgrade only**.
A fresh install works fine, so it passes casual testing and then destroys every existing
user's imported beacons and location history.

- Add a `Migration` and register it in `addMigrations(...)`
- **Never** use `fallbackToDestructiveMigration()` — recovery means redoing the macOS
  export, and `allowBackup` is false so there is no backup
- Add a migration test, including the direct path from the oldest version (users skip
  releases)
- Schemas are exported to `app/schemas/` and committed; include the new JSON in your change

### 2. Do not claim something works if you have not run it

Say plainly what was verified, what was assumed, and what could not be checked. If a change
needs a real Apple account, a real Mac, or hardware you do not have, state that rather than
implying coverage. A wrong "this works" is worse here than an honest gap, because the
failure mode is silent data loss on other people's devices.

### 3. Verify library behaviour against the installed version

FindMy.py's API changes significantly between minor versions. Methods and properties that
existed in 0.7.x are gone in 0.9.x. Before "fixing" code that looks wrong, check the pinned
version's actual source — several apparent bugs here are forced adaptations.

```bash
python -m venv .venv && .venv/bin/pip install "FindMy==<pinned version>"
```

### 4. Respect the Anisette constraints

- **Remote only.** `anisette` needs `unicorn`, a CPU emulator Chaquopy cannot build for
  Android. `app/stubs/unicorn/` is a stand-in that makes the dependency tree resolve; every
  method raises. Local Anisette does not work, and the stub is not a step toward it.
- **Sessions are bound to one server's machine identity.** Changing Anisette server requires
  a re-login. That is inherent to how Apple binds the session, not a bug to work around —
  rewriting the stored provider would leave the app running but silently failing auth.

### 5. Never bundle an AMap API key

AMap issues keys per developer account, bound to a package name and signing fingerprint, and
expects the key holder to be the app's operator. Users supply their own in Settings, applied
at runtime via `MapsInitializer.setApiKey`.

Selecting AMap without a key must not save — both in Settings and on the first-run screen.

### 6. Keep both key-alignment paths working

Without a `KeyAlignmentRecord`, `FindMyAccessory` starts at index 0 from its pairing date, so
the first fetch searches the tag's entire history — ~50,000 keys for an 18-month-old tag, at
Apple's ~290-keys-per-request limit. That is an account-flagging risk, not just slowness.

- Exports from format `0.0.2` onward carry the record; it is passed to
  `FindMyAccessory.from_plist(plist, key_alignment_plist)`
- Older exports have none and fall back to the probe in `main.py`
- Changes must not break either path

### 7. Add map providers behind `IMapProvider`

Do not branch inside `MapsActivity`. The abstraction exists precisely so a new provider is a
new implementation — a third party added a MapLibre provider in ~80 lines because of it.

### 8. Attribute other people's work

When merging a contributor's changes, preserve authorship — cherry-pick, or set `--author`,
rather than copying file contents into your own commit. Credit them in the PR description.

---

## Building and testing

See **[TESTING.md](./TESTING.md)** for every suite. Short version:

```bash
./gradlew testAll           # everything that needs no device
./gradlew testAllOnDevice   # the above plus instrumented tests
```

Python must be on `PATH` — the build shells out to it to generate the unicorn stub wheel.

**Type-check Python with pyright, not mypy.** The editors used here run Pylance, which is
pyright; default mypy is far more lenient and will pass code your editor flags.

```bash
python -m pyright app/src/main/python/main.py
```

## Windows traps

Each of these has already produced a hang that looked like something else:

- **`python3` is usually a trap.** It resolves to a zero-byte Microsoft Store alias in
  `%LOCALAPPDATA%\Microsoft\WindowsApps\` which hangs forever when run non-interactively
  rather than failing. Prefer `python`; reject candidates whose file length is zero.
- **Git Bash rewrites POSIX paths.** `adb push ... /sdcard/Download/` becomes
  `C:/Program Files/Git/sdcard/...`. `MSYS_NO_PATHCONV=1` fixes that but *also* ~~stops~~
  `JAVA_HOME` being translated, so `gradlew.bat` gets a POSIX path and dies. Never set it
  globally.
- **adb appears to hang from Git Bash** on `start-server`, because MSYS waits on the
  daemon's inherited handles. Use PowerShell, or have Android Studio open — it runs its own
  adb server.
- **Run instrumented tests from the Android Studio UI.** They take ~25 seconds. If a CLI run
  seems to hang, suspect the environment — and never wrap the command in a `grep` that can
  swallow the error message.

## Conventions

- Java, not Kotlin, in `app/`. Match the surrounding style.
- Entities use Lombok `@Builder`; construct them that way.
- Comments explain *why*, especially where behaviour looks wrong but is forced by Apple's or
  FindMy.py's API.
- User-facing strings go in `values/strings.xml` with translations in every supported locale.
- Prefer fixing the root cause to adding a workaround.
